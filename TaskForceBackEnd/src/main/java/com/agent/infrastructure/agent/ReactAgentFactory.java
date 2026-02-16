package com.agent.infrastructure.agent;

import com.agent.domain.team.lead.TeamLeadToolProvider;
import com.agent.domain.team.lead.hook.LeadIdleYieldHook;
import com.agent.domain.team.lead.scheduling.LeadSchedulingDecisionService;
import com.agent.domain.worker.execution.WorkerToolProvider;
import com.agent.infrastructure.agent.hook.ModelCallLimitHook;
import com.agent.infrastructure.agent.interceptor.ContextEnrichingToolInterceptor;
import com.agent.infrastructure.agent.interceptor.EventPublishingToolInterceptor;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.llm.ChatModelFactory;
import com.agent.infrastructure.mcp.RemoteMcpClient;
import com.agent.infrastructure.memory.DbChatMemory;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.service.AgentToolService;
import com.agent.service.SessionService;
import com.agent.service.ToolCallService;
import com.agent.infrastructure.persistence.entity.Session;
import com.agent.infrastructure.persistence.entity.SessionAgent;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReactAgent 工厂
 * 根据数据库 Agent 配置构建 ReactAgent 实例
 */
@Slf4j
@Component
public class ReactAgentFactory {

    private final AgentMapper agentMapper;
    private final ChatModelFactory chatModelFactory;
    private final RemoteMcpClient remoteMcpClient;
    private final AgentToolService agentToolService;
    private final BaseCheckpointSaver checkpointSaver;
    private final com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook skillsAgentHook;
    private final EventBus eventBus;
    private final ToolCallService toolCallService;
    private final DbChatMemory dbChatMemory;
    private final SessionService sessionService;
    private final TeamLeadToolProvider teamLeadToolProvider;
    private final WorkerToolProvider workerToolProvider;
    private final LeadSchedulingDecisionService leadSchedulingDecisionService;
    private static final double SUMMARY_TRIGGER_RATIO = 0.70d;
    private static final int DEFAULT_CONTEXT_WINDOW = 32000;
    private static final int MIN_SUMMARY_TRIGGER_TOKENS = 2000;
    private static final Pattern MODEL_WINDOW_K_PATTERN = Pattern.compile("(\\d{1,4})k");
    private static final Pattern MODEL_WINDOW_M_PATTERN = Pattern.compile("(\\d{1,2})m");

    public ReactAgentFactory(
            AgentMapper agentMapper,
            ChatModelFactory chatModelFactory,
            RemoteMcpClient remoteMcpClient,
            AgentToolService agentToolService,
            BaseCheckpointSaver checkpointSaver,
            EventBus eventBus,
            @Lazy ToolCallService toolCallService,
            DbChatMemory dbChatMemory,
            @Lazy SessionService sessionService,
            @Lazy TeamLeadToolProvider teamLeadToolProvider,
            @Lazy WorkerToolProvider workerToolProvider,
            @Lazy LeadSchedulingDecisionService leadSchedulingDecisionService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook skillsAgentHook) {
        this.agentMapper = agentMapper;
        this.chatModelFactory = chatModelFactory;
        this.remoteMcpClient = remoteMcpClient;
        this.agentToolService = agentToolService;
        this.checkpointSaver = checkpointSaver;
        this.skillsAgentHook = skillsAgentHook;
        this.eventBus = eventBus;
        this.toolCallService = toolCallService;
        this.dbChatMemory = dbChatMemory;
        this.sessionService = sessionService;
        this.teamLeadToolProvider = teamLeadToolProvider;
        this.workerToolProvider = workerToolProvider;
        this.leadSchedulingDecisionService = leadSchedulingDecisionService;
    }

    /**
     * 为 Worker 构建 ReactAgent
     *
     * @param agentId     Agent ID
     * @param instruction 执行指令
     * @param maxModelCalls 最大模型调用次数（防止无限循环）
     * @param sessionId   会话 ID
     * @param stepId      步骤 ID
     * @param stepIndex   步骤索引
     * @param instanceId  Worker 实例 ID（可选，用于 ToolContext）
     * @return ReactAgent 实例
     */
    public ReactAgent buildWorkerReactAgent(Long agentId, String instruction, int maxModelCalls,
                                           String sessionId, String stepId, Integer stepIndex, String instanceId) {
        // 1. 加载 Agent 配置
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        log.info("[ReactAgentFactory] Building ReactAgent for worker: {} (id: {})", agent.getName(), agentId);

        // 2. 创建 ChatModel
        if (agent.getProviderId() == null) {
            throw new IllegalArgumentException(
                "Agent must have a provider configured. Please set providerId for agent: " + agent.getId()
            );
        }

        String overrideModel = agent.getModel();
        ChatModel chatModel = chatModelFactory.createChatModel(agent.getProviderId(), overrideModel);
        log.info("  Using LLM Provider: {} with model: {}", agent.getProviderId(), overrideModel);

        // 3. 配置模型参数
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .temperature(agent.getTemperature().doubleValue())
                .maxTokens(agent.getMaxTokens())
                .build();

        // 4. 加载工具（不再包装，由 interceptor 统一处理）
        List<ToolCallback> tools = loadTools(agentId, agent.getName());

        // 4.1 添加 Worker 通信工具
        if (instanceId != null) {
            List<ToolCallback> workerTools = workerToolProvider.getWorkerTools();
            tools.addAll(workerTools);
            log.info("  Added {} Worker communication tools", workerTools.size());
        }

        // 5. 创建 Hooks
        List<Hook> hooks = new ArrayList<>();

        // 5.1 添加模型调用限制 Hook
        ModelCallLimitHook limitHook = new ModelCallLimitHook(maxModelCalls);
        hooks.add(limitHook);

        // 5.2 添加 SummarizationHook（控制长链路上下文）
        SummaryBudget workerSummaryBudget = resolveSummaryBudget(agent);
        SummarizationHook summarizationHook = SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(workerSummaryBudget.summaryTriggerTokens())
                .messagesToKeep(10)
                .keepFirstUserMessage(false)
                .build();
        hooks.add(summarizationHook);
        log.info("  Added SummarizationHook for worker (triggerAt: {}, contextWindow: {}, source: {}, keepMessages: 10, keepFirstUserMessage: false)",
                workerSummaryBudget.summaryTriggerTokens(),
                workerSummaryBudget.contextWindow(),
                workerSummaryBudget.source());

        // 5.3 添加 SkillsAgentHook（支持 Skill 加载和 Sandbox 工具）
        if (skillsAgentHook != null) {
            hooks.add(skillsAgentHook);
            log.info("  Added SkillsAgentHook with {} skills", skillsAgentHook.getSkillCount());
        }

        // 6. 创建 Interceptor（统一处理工具调用事件发布和持久化）
        AtomicInteger sequenceCounter = new AtomicInteger(0);
        EventPublishingToolInterceptor toolInterceptor = new EventPublishingToolInterceptor(
                sessionId, stepId, stepIndex, agentId, eventBus, toolCallService, sequenceCounter, instanceId
        );

        // 7. 构建 ReactAgent
        Builder builder = ReactAgent.builder()
                .name(agent.getName())
                .model(chatModel)
                .chatOptions(chatOptions)
                .systemPrompt(agent.getSystemPrompt())
                .tools(tools)
                .hooks(hooks)
                .interceptors(toolInterceptor)  // 注册 interceptor
                .saver(checkpointSaver);

        if (instruction != null && !instruction.isBlank()) {
            builder.instruction(instruction);
        }

        ReactAgent reactAgent = builder.build();

        log.info("[ReactAgentFactory] ReactAgent built successfully: {}", agent.getName());
        return reactAgent;
    }

    /**
     * 加载 Agent 的工具列表（不再包装，由 interceptor 统一处理）
     */
    private List<ToolCallback> loadTools(Long agentId, String agentName) {
        List<ToolCallback> allTools = new ArrayList<>();

        // 获取启用的工具 ID
        List<String> enabledToolIds = new ArrayList<>(agentToolService.getEnabledToolIds(agentId));
        log.info("  Agent {} enabled tool IDs from DB: {}", agentName, enabledToolIds);

        // 自动添加 native 工具（所有 Agent 默认拥有）
        try {
            var allAvailableTools = remoteMcpClient.listTools();
            List<String> nativeToolIds = allAvailableTools.stream()
                    .filter(tool -> tool.getId() != null && tool.getId().startsWith("native::"))
                    .map(tool -> tool.getId())
                    .toList();

            if (!nativeToolIds.isEmpty()) {
                enabledToolIds.addAll(nativeToolIds);
                log.info("  Auto-added {} native tools to agent {}", nativeToolIds.size(), agentName);
            }
        } catch (Exception e) {
            log.warn("  Failed to fetch native tools: {}", e.getMessage());
        }

        // 从 MCP Server 获取工具回调（不再包装）
        if (!enabledToolIds.isEmpty()) {
            ToolCallback[] remoteTools = remoteMcpClient.getToolCallbacks(enabledToolIds);
            log.info("  MCP Client returned {} tools for {} requested IDs", remoteTools.length, enabledToolIds.size());

            if (remoteTools.length > 0) {
                for (ToolCallback callback : remoteTools) {
                    String toolName = callback.getToolDefinition().name();
                    log.debug("  Loading tool: {}", toolName);
                    allTools.add(callback);
                }
                log.info("  Attached {} MCP tools", remoteTools.length);
            } else {
                log.warn("  No valid MCP tools found for IDs: {}", enabledToolIds);
            }
        }

        return allTools;
    }

    /**
     * 为单聊构建 ReactAgent（使用 ChatClient + MessageChatMemoryAdvisor）
     *
     * @param agentId   Agent ID
     * @param sessionId 会话 ID
     * @return ReactAgent 实例
     */
    public ReactAgent buildChatReactAgent(Long agentId, String sessionId) {
        // 1. 加载 Agent 配置
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        log.info("[ReactAgentFactory] Building ChatReactAgent for single chat: {} (id: {})", agent.getName(), agentId);

        // 2. 创建 ChatModel
        if (agent.getProviderId() == null) {
            throw new IllegalArgumentException(
                "Agent must have a provider configured. Please set providerId for agent: " + agent.getId()
            );
        }

        String overrideModel = agent.getModel();
        ChatModel chatModel = chatModelFactory.createChatModel(agent.getProviderId(), overrideModel);
        log.info("  Using LLM Provider: {} with model: {}", agent.getProviderId(), overrideModel);

        // 3. 加载工具（不再包装，由 interceptor 统一处理）
        List<ToolCallback> tools = loadTools(agentId, agent.getName());

        // 4. 构建 ChatClient（挂 MessageChatMemoryAdvisor + MCP 工具）
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(agent.getSystemPrompt())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(dbChatMemory)
                        .conversationId(sessionId)  // 指定 conversationId 为 sessionId
                        .build())
                .defaultToolCallbacks(tools)
                .defaultOptions(OpenAiChatOptions.builder()
                        .temperature(agent.getTemperature().doubleValue())
                        .maxTokens(agent.getMaxTokens())
                        .build())
                .build();

        // 5. 创建 Interceptor（统一处理工具调用事件发布和持久化）
        AtomicInteger sequenceCounter = new AtomicInteger(0);
        EventPublishingToolInterceptor toolInterceptor = new EventPublishingToolInterceptor(
                sessionId, null, null, agentId, eventBus, toolCallService, sequenceCounter, null
        );

        // 6. 创建 Hooks
        List<Hook> hooks = new ArrayList<>();

        // 6.1 添加 SummarizationHook（智能摘要，替代简单截断）
        // 使用相同的模型进行摘要（可以配置为更便宜的模型）
        SummaryBudget chatSummaryBudget = resolveSummaryBudget(agent);
        SummarizationHook summarizationHook = SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(chatSummaryBudget.summaryTriggerTokens())
                .messagesToKeep(8)  // 保留最近 8 条消息
                .build();
        hooks.add(summarizationHook);
        log.info("  Added SummarizationHook (triggerAt: {}, contextWindow: {}, source: {}, keepMessages: 8)",
                chatSummaryBudget.summaryTriggerTokens(),
                chatSummaryBudget.contextWindow(),
                chatSummaryBudget.source());

        // 6.2 添加 SkillsAgentHook（支持 Skill 加载和 Sandbox 工具）
        if (skillsAgentHook != null) {
            hooks.add(skillsAgentHook);
            log.info("  Added SkillsAgentHook with {} skills", skillsAgentHook.getSkillCount());
        }

        // 7. 构建 ReactAgent
        Builder builder = ReactAgent.builder()
                .name(agent.getName())
                .chatClient(chatClient)
                .tools(tools)  // 🔧 修复：同时传递 tools 给 ReactAgent
                .interceptors(toolInterceptor)  // 注册 interceptor
                .hooks(hooks)  // 注册 hooks
                .saver(checkpointSaver);

        ReactAgent reactAgent = builder.build();

        log.info("[ReactAgentFactory] ChatReactAgent built successfully: {}", agent.getName());
        return reactAgent;
    }

    private SummaryBudget resolveSummaryBudget(Agent agent) {
        Integer configuredContextWindow = agent.getContextWindow();
        if (configuredContextWindow != null && configuredContextWindow > 0) {
            return new SummaryBudget(
                    configuredContextWindow,
                    toSummaryTrigger(configuredContextWindow),
                    "agent.contextWindow"
            );
        }

        Integer inferredContextWindow = inferContextWindowFromModel(agent.getModel());
        if (inferredContextWindow != null && inferredContextWindow > 0) {
            return new SummaryBudget(
                    inferredContextWindow,
                    toSummaryTrigger(inferredContextWindow),
                    "model-name-inferred"
            );
        }

        return new SummaryBudget(
                DEFAULT_CONTEXT_WINDOW,
                toSummaryTrigger(DEFAULT_CONTEXT_WINDOW),
                "default"
        );
    }

    private int toSummaryTrigger(int contextWindow) {
        int trigger = (int) Math.floor(contextWindow * SUMMARY_TRIGGER_RATIO);
        return Math.max(MIN_SUMMARY_TRIGGER_TOKENS, trigger);
    }

    private Integer inferContextWindowFromModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String normalized = modelName.toLowerCase(Locale.ROOT);

        Matcher mMatcher = MODEL_WINDOW_M_PATTERN.matcher(normalized);
        if (mMatcher.find()) {
            int value = Integer.parseInt(mMatcher.group(1));
            return value * 1_000_000;
        }

        Matcher kMatcher = MODEL_WINDOW_K_PATTERN.matcher(normalized);
        if (kMatcher.find()) {
            int value = Integer.parseInt(kMatcher.group(1));
            return value * 1_000;
        }

        return null;
    }

    private record SummaryBudget(int contextWindow, int summaryTriggerTokens, String source) {}

    /**
     * 为 Team Lead 构建 ReactAgent
     *
     * @param agentId       Agent ID
     * @param sessionId     会话 ID
     * @param systemPrompt  系统提示词
     * @param maxModelCalls 最大模型调用次数
     * @param chatModel     预先构建的聊天模型（可选）
     * @param chatOptions   预先配置的模型参数（可选）
     * @return ReactAgent 实例
     */
    public ReactAgent buildTeamLeadAgent(Long agentId,
                                         String sessionId,
                                         String systemPrompt,
                                         int maxModelCalls,
                                         ChatModel chatModel,
                                         OpenAiChatOptions chatOptions,
                                         boolean enableIdleYield) {
        // 1. 加载 Agent 配置
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        log.info("[ReactAgentFactory] Building ReactAgent for Team Lead: {} (id: {})", agent.getName(), agentId);

        // 2. 解析 ChatModel / ChatOptions（优先使用调用方传入值）
        ChatModel finalChatModel = chatModel;
        OpenAiChatOptions finalChatOptions = chatOptions;

        if (finalChatModel == null) {
            if (agent.getProviderId() == null) {
                throw new IllegalArgumentException(
                        "Agent must have a provider configured. Please set providerId for agent: " + agent.getId()
                );
            }
            String overrideModel = agent.getModel();
            finalChatModel = chatModelFactory.createChatModel(agent.getProviderId(), overrideModel);
            log.info("  Using LLM Provider: {} with model: {}", agent.getProviderId(), overrideModel);
        }

        if (finalChatOptions == null) {
            finalChatOptions = OpenAiChatOptions.builder()
                    .temperature(agent.getTemperature().doubleValue())
                    .maxTokens(agent.getMaxTokens())
                    .build();
        }

        // 4. 加载 Lead 内部工具（不是 MCP 工具）
        List<ToolCallback> leadTools = teamLeadToolProvider.getLeadTools();
        log.info("  Loaded {} Lead internal tools", leadTools.size());

        // 5. 创建 Hooks
        List<Hook> hooks = new ArrayList<>();

        // 5.1 添加模型调用限制 Hook
        ModelCallLimitHook limitHook = new ModelCallLimitHook(maxModelCalls);
        hooks.add(limitHook);

        // 5.2 添加 Lead 空转让出 Hook（系统状态机判定）
        if (enableIdleYield) {
            LeadIdleYieldHook leadIdleYieldHook = new LeadIdleYieldHook(sessionId, leadSchedulingDecisionService);
            hooks.add(leadIdleYieldHook);
        } else {
            log.info("[ReactAgentFactory] Lead idle-yield hook disabled for this round: sessionId={}", sessionId);
        }

        // 5.3 添加 SummarizationHook（按上下文窗口 70% 触发）
        SummaryBudget leadSummaryBudget = resolveSummaryBudget(agent);
        SummarizationHook leadSummarizationHook = SummarizationHook.builder()
                .model(finalChatModel)
                .maxTokensBeforeSummary(leadSummaryBudget.summaryTriggerTokens())
                .messagesToKeep(10)
                .build();
        hooks.add(leadSummarizationHook);
        log.info("  Added SummarizationHook for lead (triggerAt: {}, contextWindow: {}, source: {}, keepMessages: 10)",
                leadSummaryBudget.summaryTriggerTokens(),
                leadSummaryBudget.contextWindow(),
                leadSummaryBudget.source());

        // 5.4 添加 SkillsAgentHook（支持 Skill 加载和 Sandbox 工具）
        if (skillsAgentHook != null) {
            hooks.add(skillsAgentHook);
            log.info("  Added SkillsAgentHook with {} skills", skillsAgentHook.getSkillCount());
        }

        // 6. 创建 Interceptor（统一处理上下文注入 + 工具调用事件发布和持久化）
        ContextEnrichingToolInterceptor contextInterceptor = new ContextEnrichingToolInterceptor(sessionId, null);
        AtomicInteger sequenceCounter = new AtomicInteger(0);
        EventPublishingToolInterceptor toolInterceptor = new EventPublishingToolInterceptor(
                sessionId, null, null, agentId, eventBus, toolCallService, sequenceCounter, null
        );

        // 7. 构建 ReactAgent
        ReactAgent reactAgent = ReactAgent.builder()
                .name("TeamLead-" + agent.getName())
                .model(finalChatModel)
                .chatOptions(finalChatOptions)
                .systemPrompt(systemPrompt)
                .tools(leadTools)
                .hooks(hooks)
                .interceptors(contextInterceptor, toolInterceptor)
                .saver(checkpointSaver)
                .build();

        log.info("[ReactAgentFactory] Team Lead ReactAgent built successfully: {}", agent.getName());
        return reactAgent;
    }
}
