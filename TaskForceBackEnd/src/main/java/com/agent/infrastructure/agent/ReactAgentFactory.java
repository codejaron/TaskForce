package com.agent.infrastructure.agent;

import com.agent.infrastructure.agent.hook.ModelCallLimitHook;
import com.agent.infrastructure.config.EventPublishingToolCallback;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.llm.ChatModelFactory;
import com.agent.infrastructure.mcp.RemoteMcpClient;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.service.AgentToolService;
import com.agent.service.ToolCallService;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    public ReactAgentFactory(
            AgentMapper agentMapper,
            ChatModelFactory chatModelFactory,
            RemoteMcpClient remoteMcpClient,
            AgentToolService agentToolService,
            BaseCheckpointSaver checkpointSaver,
            EventBus eventBus,
            @Lazy ToolCallService toolCallService,
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
     * @return ReactAgent 实例
     */
    public ReactAgent buildWorkerReactAgent(Long agentId, String instruction, int maxModelCalls,
                                           String sessionId, String stepId, Integer stepIndex) {
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

        // 4. 加载工具
        List<ToolCallback> tools = loadTools(agentId, agent.getName(), sessionId, stepId, stepIndex);

        // 5. 创建 Hooks
        List<Hook> hooks = new ArrayList<>();

        // 5.1 添加模型调用限制 Hook
        ModelCallLimitHook limitHook = new ModelCallLimitHook(maxModelCalls);
        hooks.add(limitHook);

        // 5.2 添加 SkillsAgentHook（支持 Skill 加载和 Sandbox 工具）
        if (skillsAgentHook != null) {
            hooks.add(skillsAgentHook);
            log.info("  Added SkillsAgentHook with {} skills", skillsAgentHook.getSkillCount());
        }

        // 6. 构建 ReactAgent
        ReactAgent reactAgent = ReactAgent.builder()
                .name(agent.getName())
                .model(chatModel)
                .chatOptions(chatOptions)
                .instruction(instruction)
                .systemPrompt(agent.getSystemPrompt())
                .tools(tools)
                .hooks(hooks)
                .saver(checkpointSaver)
                .build();

        log.info("[ReactAgentFactory] ReactAgent built successfully: {}", agent.getName());
        return reactAgent;
    }

    /**
     * 加载 Agent 的工具列表（包装为 EventPublishingToolCallback）
     */
    private List<ToolCallback> loadTools(Long agentId, String agentName, String sessionId, String stepId, Integer stepIndex) {
        List<ToolCallback> allTools = new ArrayList<>();
        AtomicInteger sequenceCounter = new AtomicInteger(0);

        // 获取启用的工具 ID
        List<String> enabledToolIds = new ArrayList<>(agentToolService.getEnabledToolIds(agentId));

        // 自动添加所有 native 工具
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

        // 从 MCP Server 获取工具回调并包装
        if (!enabledToolIds.isEmpty()) {
            ToolCallback[] remoteTools = remoteMcpClient.getToolCallbacks(enabledToolIds);
            if (remoteTools.length > 0) {
                for (ToolCallback callback : remoteTools) {
                    // 获取 serverName（从 toolId 中提取 providerName）
                    String toolName = callback.getToolDefinition().name();
                    String serverName = extractProviderName(toolName);

                    // 包装为事件发布回调
                    ToolCallback wrapped = new EventPublishingToolCallback(
                            callback, sessionId, stepId, stepIndex, agentId, serverName,
                            eventBus, toolCallService, sequenceCounter
                    );
                    allTools.add(wrapped);
                }
                log.info("  Attached {} MCP tools (with event publishing)", remoteTools.length);
            } else {
                log.warn("  No valid MCP tools found");
            }
        }

        return allTools;
    }

    /**
     * 从工具 ID 中提取 Provider 名称
     * 格式: {providerName}::{toolName}
     */
    private String extractProviderName(String toolId) {
        if (toolId != null && toolId.contains("::")) {
            return toolId.substring(0, toolId.indexOf("::"));
        }
        return "unknown";
    }
}
