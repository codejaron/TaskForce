package com.agent.infrastructure.agent;

import com.agent.infrastructure.agent.hook.ModelCallLimitHook;
import com.agent.infrastructure.llm.ChatModelFactory;
import com.agent.infrastructure.mcp.RemoteMcpClient;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.service.AgentToolService;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ReactAgent 工厂
 * 根据数据库 Agent 配置构建 ReactAgent 实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactAgentFactory {

    private final AgentMapper agentMapper;
    private final ChatModelFactory chatModelFactory;
    private final RemoteMcpClient remoteMcpClient;
    private final AgentToolService agentToolService;
    private final BaseCheckpointSaver checkpointSaver;

    /**
     * 为 Worker 构建 ReactAgent
     *
     * @param agentId     Agent ID
     * @param instruction 执行指令
     * @param maxModelCalls 最大模型调用次数（防止无限循环）
     * @return ReactAgent 实例
     */
    public ReactAgent buildWorkerReactAgent(Long agentId, String instruction, int maxModelCalls) {
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
        List<ToolCallback> tools = loadTools(agentId, agent.getName());

        // 5. 创建 Hooks
        List<Hook> hooks = new ArrayList<>();

        // 5.1 添加模型调用限制 Hook
        ModelCallLimitHook limitHook = new ModelCallLimitHook(maxModelCalls);
        hooks.add(limitHook);

        // 5.2 TODO: 添加 SkillsAgentHook（在 Task #7 中实现）

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
     * 加载 Agent 的工具列表
     */
    private List<ToolCallback> loadTools(Long agentId, String agentName) {
        List<ToolCallback> allTools = new ArrayList<>();

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

        // 从 MCP Server 获取工具回调
        if (!enabledToolIds.isEmpty()) {
            ToolCallback[] remoteTools = remoteMcpClient.getToolCallbacks(enabledToolIds);
            if (remoteTools.length > 0) {
                allTools.addAll(List.of(remoteTools));
                log.info("  Attached {} MCP tools", remoteTools.length);
            } else {
                log.warn("  No valid MCP tools found");
            }
        }

        return allTools;
    }
}
