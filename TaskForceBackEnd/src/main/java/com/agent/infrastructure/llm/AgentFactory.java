package com.agent.infrastructure.llm;

import com.agent.domain.tool.ToolInfo;
import com.agent.infrastructure.mcp.RemoteMcpClient;
import com.agent.infrastructure.config.EventPublishingToolCallback;
import com.agent.infrastructure.event.EventBus;
import com.agent.service.AgentToolService;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.infrastructure.persistence.mapper.LLMProviderMapper;
import com.agent.service.ToolCallService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体工厂
 * 根据 AgentProfile 动态构建定制化的 ChatClient
 * 核心功能：
 * 1. 实现能力隔离，每个智能体只能访问被授权的 MCP 工具
 * 2. 为每个会话维护独立的对话记忆，避免重复发送历史
 * 3. 从数据库加载 Agent 配置，使用用户自定义的 LLMProvider
 *
 * 注意：所有Agent必须配置LLM Provider，不再支持默认模型
 */
@Slf4j
@Service
public class AgentFactory {

    private final RemoteMcpClient remoteMcpClient;
    private final ChatModelFactory chatModelFactory; // 动态模型工厂
    private final AgentMapper agentMapper;
    private final LLMProviderMapper providerMapper;
    private final AgentToolService agentToolService; // Agent工具服务
    private final EventBus eventBus;
    private final ToolCallService toolCallService;

    public AgentFactory(
            RemoteMcpClient remoteMcpClient,
            ChatModelFactory chatModelFactory,
            AgentMapper agentMapper,
            LLMProviderMapper providerMapper,
            AgentToolService agentToolService,
            EventBus eventBus,
            @Lazy ToolCallService toolCallService) {
        this.remoteMcpClient = remoteMcpClient;
        this.chatModelFactory = chatModelFactory;
        this.agentMapper = agentMapper;
        this.providerMapper = providerMapper;
        this.agentToolService = agentToolService;
        this.eventBus = eventBus;
        this.toolCallService = toolCallService;
    }


    /**
     * 根据数据库中的 Agent ID 构建 ChatClient（向后兼容，不带 stepId）
     */
    public ChatClient buildClientForDatabaseAgent(Long agentId, String sessionId) {
        return buildClientForDatabaseAgent(agentId, sessionId, null, null);
    }

    /**
     * 根据数据库中的 Agent ID 构建 ChatClient（支持工具调用事件追踪）
     *
     * @param agentId   数据库中的智能体ID
     * @param sessionId 会话ID
     * @param stepId    步骤ID（用于工具调用事件关联，可为 null）
     * @param stepIndex 步骤索引（用于工具调用事件关联，可为 null）
     * @return ChatClient
     */
    public ChatClient buildClientForDatabaseAgent(Long agentId, String sessionId, String stepId, Integer stepIndex) {
        // 1. 从数据库加载 Agent
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        log.info("Building ChatClient for database agent: {} (id: {}, stepId: {}, stepIndex: {})", 
                agent.getName(), agentId, stepId, stepIndex);

        // 2. 确定使用的 ChatModel
        // 强制要求Agent配置Provider
        if (agent.getProviderId() == null) {
            throw new IllegalArgumentException(
                "Agent must have a provider configured. Please set providerId for agent: " + agent.getId()
            );
        }

        // 使用用户自定义的 LLM Provider，并允许 agent 指定具体模型覆盖 provider 的默认
        String overrideModel = agent.getModel();
        ChatModel chatModel = chatModelFactory.createChatModel(agent.getProviderId(), overrideModel);
        log.info("  Using custom LLM Provider: {} with override model: {}", agent.getProviderId(), overrideModel);

        // 设置模型参数
        OpenAiChatOptions clientOptions = OpenAiChatOptions.builder()
                .temperature(agent.getTemperature().doubleValue())
                .maxTokens(agent.getMaxTokens())
                .build();


        // 4. 创建 ChatClient Builder
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // 5. 设置 System Prompt
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isEmpty()) {
            builder.defaultSystem(agent.getSystemPrompt());
        }

        // 6. 设置默认模型参数（温度、maxTokens 等）
        builder.defaultOptions(clientOptions);


        // 8. 挂载工具（从 mcp-server 获取），并用 EventPublishingToolCallback 包装
        List<FunctionCallback> allTools = new ArrayList<>();
        AtomicInteger sequenceCounter = new AtomicInteger(0);

        // 8.1 添加远程 MCP 工具（从 mcp-server 获取）
        List<String> enabledToolIds = new ArrayList<>(agentToolService.getEnabledToolIds(agentId));
        
        // 8.1.1 自动添加所有 native 工具（所有 Agent 默认拥有）
        try {
            List<ToolInfo> allAvailableTools = remoteMcpClient.listTools();
            List<String> nativeToolIds = allAvailableTools.stream()
                    .filter(tool -> tool.getId() != null && tool.getId().startsWith("native::"))
                    .map(ToolInfo::getId)
                    .toList();
            
            if (!nativeToolIds.isEmpty()) {
                enabledToolIds.addAll(nativeToolIds);
                log.info("  Auto-added {} native tools to agent {}", nativeToolIds.size(), agent.getName());
            }
        } catch (Exception e) {
            log.warn("  Failed to fetch native tools: {}", e.getMessage());
        }
        
        if (!enabledToolIds.isEmpty()) {
            ToolCallback[] remoteTools = remoteMcpClient.getToolCallbacks(enabledToolIds);
            if (remoteTools.length > 0) {
                for (ToolCallback callback : remoteTools) {
                    // 获取 serverName（从 toolId 中提取 providerName）
                    String serverName = extractProviderName(callback.getName());
                    // 包装为事件发布回调
                    ToolCallback wrapped = wrapWithEventPublishing(
                            callback, sessionId, stepId, stepIndex, agentId, serverName, sequenceCounter
                    );
                    allTools.add(wrapped);
                }
                log.info("  Attached {} MCP tools (with event publishing)", remoteTools.length);
            } else {
                log.warn("  No valid MCP tools found");
            }
        }

        // 8.2 注册到 ChatClient
        if (!allTools.isEmpty()) {
            builder.defaultTools(allTools.toArray(new FunctionCallback[0]));
            log.info("  Total tools attached to agent {}: {}", agent.getName(), allTools.size());
        } else {
            log.info("  No tools attached to agent {}", agent.getName());
        }

        return builder.build();
    }

    /**
     * 用 EventPublishingToolCallback 包装工具回调（MCP 工具）
     */
    private ToolCallback wrapWithEventPublishing(
            ToolCallback delegate,
            String sessionId,
            String stepId,
            Integer stepIndex,
            Long agentId,
            String serverName,
            AtomicInteger sequenceCounter) {
        return new EventPublishingToolCallback(
                delegate,
                sessionId,
                stepId,
                stepIndex,
                agentId,
                serverName,
                eventBus,
                toolCallService,
                sequenceCounter
        );
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
