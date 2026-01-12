package com.agent.factory;

import com.agent.config.AutoToolConfiguration;
import com.agent.service.AgentToolService;
import com.agent.entity.Agent;
import com.agent.mapper.AgentMapper;
import com.agent.mapper.LLMProviderMapper;
import com.agent.mcp.McpToolRegistry;
import com.agent.model.AgentProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
@RequiredArgsConstructor
public class AgentFactory {

    private final McpToolRegistry mcpToolRegistry;
    private final AutoToolConfiguration autoToolConfiguration; // 原生工具配置
    private final ChatModelFactory chatModelFactory; // 动态模型工厂
    private final AgentMapper agentMapper;
    private final LLMProviderMapper providerMapper;
    private final AgentToolService agentToolService; // Agent工具服务

    /**
     * 会话记忆缓存：sessionId -> ChatMemory
     * 同一个会话中的所有智能体共享同一个记忆
     */
    private final Map<String, ChatMemory> sessionMemoryCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话的 ChatMemory
     */
    public ChatMemory getOrCreateSessionMemory(String sessionId) {
        return sessionMemoryCache.computeIfAbsent(sessionId, k -> {
            log.info("Creating new ChatMemory for session: {}", sessionId);
            return new InMemoryChatMemory();
        });
    }

    /**
     * 清除会话记忆（会话结束时调用）
     */
    public void clearSessionMemory(String sessionId) {
        ChatMemory memory = sessionMemoryCache.remove(sessionId);
        if (memory != null) {
            log.info("Cleared ChatMemory for session: {}", sessionId);
        }
    }

    /**
     * 根据数据库中的 Agent ID 构建 ChatClient（新方法，使用数据库）
     *
     * @param agentId 数据库中的智能体ID
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

        // 3. 获取会话记忆
        ChatMemory sessionMemory = getOrCreateSessionMemory(sessionId);

        // 4. 创建 ChatClient Builder
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // 5. 设置 System Prompt
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isEmpty()) {
            builder.defaultSystem(agent.getSystemPrompt());
        }

        // 6. 设置默认模型参数（温度、maxTokens 等）
        builder.defaultOptions(clientOptions);

        // 7. 添加记忆顾问
        builder.defaultAdvisors(
                MessageChatMemoryAdvisor.builder(sessionMemory)
                        .conversationId(sessionId)
                        .chatMemoryRetrieveSize(10)
                        .build()
        );

        // 8. 挂载工具（MCP + 原生）
        List<FunctionCallback> allTools = new ArrayList<>();

        // 8.1 添加 MCP 工具（从数据库加载）
        List<String> enabledToolIds = agentToolService.getEnabledToolIds(agentId);
        if (!enabledToolIds.isEmpty()) {
            ToolCallback[] mcpCallbacks = mcpToolRegistry.getToolCallbacks(enabledToolIds);
            if (mcpCallbacks.length > 0) {
                allTools.addAll(Arrays.asList(mcpCallbacks));
                log.info("  Attached {} MCP tools", mcpCallbacks.length);
            } else {
                log.warn("  No valid MCP tools found (tools may have been removed)");
            }
        }

        // 8.2 添加原生 @Tool 工具（所有 Worker 都可用，传入 sessionId 用于跨线程传递上下文）
        FunctionCallback[] nativeCallbacks = autoToolConfiguration.getToolCallbacks(sessionId);
        if (nativeCallbacks.length > 0) {
            allTools.addAll(Arrays.asList(nativeCallbacks));
            log.info("  Attached {} native @Tool tools (with sessionId: {})", nativeCallbacks.length, sessionId);
        }

        // 8.3 注册到 ChatClient
        if (!allTools.isEmpty()) {
            builder.defaultTools(allTools.toArray(new FunctionCallback[0]));
            log.info("  Total tools attached to agent {}: {}", agent.getName(), allTools.size());
        } else {
            log.info("  No tools attached to agent {}", agent.getName());
        }

        return builder.build();
    }
}
