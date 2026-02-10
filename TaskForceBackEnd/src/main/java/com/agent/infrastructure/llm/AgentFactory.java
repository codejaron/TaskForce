package com.agent.infrastructure.llm;

import com.agent.common.dto.ToolInfo;
import com.agent.infrastructure.mcp.RemoteMcpClient;
import com.agent.service.AgentToolService;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.infrastructure.persistence.mapper.LLMProviderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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

    public AgentFactory(
            RemoteMcpClient remoteMcpClient,
            ChatModelFactory chatModelFactory,
            AgentMapper agentMapper,
            LLMProviderMapper providerMapper,
            AgentToolService agentToolService) {
        this.remoteMcpClient = remoteMcpClient;
        this.chatModelFactory = chatModelFactory;
        this.agentMapper = agentMapper;
        this.providerMapper = providerMapper;
        this.agentToolService = agentToolService;
    }


    /**
     * 根据数据库中的 Agent ID 构建 ChatClient
     *
     * @param agentId   数据库中的智能体ID
     * @param sessionId 会话ID
     * @return ChatClient
     */
    public ChatClient buildClientForDatabaseAgent(Long agentId, String sessionId) {
        // 1. 从数据库加载 Agent
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        log.info("Building ChatClient for database agent: {} (id: {})", agent.getName(), agentId);

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


        // 8. 挂载工具（从 mcp-server 获取）
        List<ToolCallback> allTools = new ArrayList<>();

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
                    allTools.add(callback);
                }
                log.info("  Attached {} MCP tools", remoteTools.length);
            } else {
                log.warn("  No valid MCP tools found");
            }
        }

        // 8.2 注册到 ChatClient
        if (!allTools.isEmpty()) {
            builder.defaultToolCallbacks(allTools);
            log.info("  Total tools attached to agent {}: {}", agent.getName(), allTools.size());
        } else {
            log.info("  No tools attached to agent {}", agent.getName());
        }

        return builder.build();
    }
}
