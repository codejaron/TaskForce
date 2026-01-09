package com.agent.infrastructure.prompt;

import com.agent.domain.model.context.TaskContext;
import com.agent.domain.model.plan.PlanStep;
import com.agent.entity.Message;
import com.agent.model.AgentProfile;
import com.agent.model.ToolInfo;
import com.agent.service.AgentToolService;
import com.agent.mcp.McpToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Prompt管理器
 * 集中管理所有Prompt模板和组装逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptManager {

    private final AgentToolService agentToolService;
    private final McpToolRegistry mcpToolRegistry;


    /**
     * Planner Agent Prompt模板
     * 用于生成执行计划
     */
    public static final String PLANNER_PROMPT = """
        你是一个任务规划专家。根据用户的目标，生成一个执行计划。

        【可用的 Worker 列表】
        %s

        【用户目标】
        %s

        【输出格式要求】
        %s

        【规则】
        1. 如果用户目标模糊，优先选择 type=question 询问用户
        2. 步骤数量控制在 10 步以内
        3. 每个步骤必须分配给一个具体的 Worker
        4. 最后一步通常是汇总/输出步骤
        5. assignedAgentId 必须是可用 Worker 列表中的真实 ID
        """;

    /**
     * Replanner Agent Prompt模板
     * 用于重新规划
     */
    public static final String REPLANNER_PROMPT = """
        你是一个任务重规划专家。当前计划在执行过程中遇到了问题，需要调整。

        【当前计划】
        目标: %s
        已完成步骤: %d
        总步骤数: %d

        【阻塞信息】
        步骤 %d (%s) 执行失败
        原因: %s

        【要求】
        1. 分析失败原因
        2. 决定是否需要修改后续步骤
        3. 输出调整后的计划（从当前步骤开始）

        【输出格式要求】
        %s

        【规则】
        1. 步骤编号从 1 开始重新计数
        """;

    /**
     * Worker Agent Prompt模板
     * 用于执行具体任务
     */
    public static final String WORKER_PROMPT_TEMPLATE = """
        你是一个任务执行专家：%s

        【你的角色】
        %s

        【当前任务】
        %s

        【期望输出】
        %s

        【执行规则】
        1. 严格按照任务指令执行
        2. 如果遇到无法完成的情况，输出 "BLOCKED: 原因"
        3. 如果需要用户提供更多信息，输出 "NEED_USER_INPUT: 问题"
        4. 完成后直接输出结果，不需要额外的格式
        """;

    /**
     * 构建 Planner Prompt
     * @param workers 可用的Worker列表
     * @param userGoal 用户目标
     * @param formatInstructions BeanOutputParser 生成的格式说明
     * @return 完整的Planner Prompt
     */
    public String buildPlannerPrompt(List<AgentProfile> workers, String userGoal, String formatInstructions) {
        String rosterText = formatWorkerRoster(workers);
        String fullPrompt = String.format(PLANNER_PROMPT, rosterText, userGoal, formatInstructions);

        log.debug("[PromptManager] 构建 Planner Prompt, workers={}, goal={}",
                workers.size(), userGoal);

        return fullPrompt;
    }

    /**
     * 构建 Replanner Prompt
     * @param currentGoal 当前目标
     * @param completedSteps 已完成步骤数
     * @param totalSteps 总步骤数
     * @param blockedStepIndex 阻塞步骤索引
     * @param blockedStepDesc 阻塞步骤描述
     * @param blockedReason 阻塞原因
     * @param formatInstructions BeanOutputParser 生成的格式说明
     * @return 完整的Replanner Prompt
     */
    public String buildReplannerPrompt(
            String currentGoal,
            int completedSteps,
            int totalSteps,
            int blockedStepIndex,
            String blockedStepDesc,
            String blockedReason,
            String formatInstructions) {

        String fullPrompt = String.format(REPLANNER_PROMPT,
                currentGoal,
                completedSteps,
                totalSteps,
                blockedStepIndex,
                blockedStepDesc,
                blockedReason,
                formatInstructions
        );

        log.debug("[PromptManager] 构建 Replanner Prompt, goal={}, blocked at step {}",
                currentGoal, blockedStepIndex);

        return fullPrompt;
    }

    /**
     * 构建带上下文的 Worker Prompt
     * @param context 任务上下文
     * @param worker Worker配置
     * @return 完整的Worker Prompt
     */
    public String buildWorkerPromptWithContext(TaskContext context, AgentProfile worker) {
        StringBuilder prompt = new StringBuilder();

        // 1. 基础角色说明
        prompt.append("你是一个任务执行专家：").append(worker.getName()).append("\n\n");
        prompt.append("【你的角色】\n");
        prompt.append(worker.getSystemPrompt() != null ? worker.getSystemPrompt() : "执行任务").append("\n\n");

        // 2. 用户目标
        if (context.getUserGoal() != null && !context.getUserGoal().isEmpty()) {
            prompt.append("【用户目标】\n");
            prompt.append(context.getUserGoal()).append("\n\n");
        }

        // 3. 已知信息（共享黑板）
        if (context.hasSharedData()) {
            prompt.append("【已知信息（共享黑板）】\n");
            prompt.append(formatSharedData(context.getSharedData())).append("\n\n");
        }

        // 4. 对话历史
        if (context.hasHistory()) {
            prompt.append("【对话历史】\n");
            prompt.append(formatRecentHistory(context.getRecentHistory())).append("\n\n");
        }

        // 5. 当前任务
        PlanStep step = context.getCurrentStep();
        if (step != null) {
            prompt.append("【当前任务】\n");
            prompt.append("步骤 ").append(step.getStepIndex()).append(": ").append(step.getDescription()).append("\n");
            prompt.append("指令: ").append(step.getInstruction()).append("\n");
            if (step.getExpectedOutput() != null && !step.getExpectedOutput().isEmpty()) {
                prompt.append("预期输出: ").append(step.getExpectedOutput()).append("\n");
            }
            prompt.append("\n");
        }

        // 6. 输出协议
        prompt.append("""
            【输出协议】
            如果你产生了需要传递给后续步骤的关键产物（如代码、搜索结果、草稿），
            请务必使用 XML 标签包裹。格式如下：

            <artifact key="变量名">
            这里是具体内容
            </artifact>

            示例：
            <artifact key="search_results">
            1. 结果A
            2. 结果B
            </artifact>

            <artifact key="generated_code">
            public class Example {
                // code here
            }
            </artifact>

            未包裹的内容将被视为普通闲聊，不会被系统记忆。

            【执行规则】
            1. 严格按照任务指令执行
            2. 如果遇到无法完成的情况，输出 "BLOCKED: 原因"
            3. 如果需要用户提供更多信息，输出 "NEED_USER_INPUT: 问题"
            4. 完成后直接输出结果，不需要额外的格式
            """);

        log.debug("[PromptManager] Built Worker Prompt with context: worker={}, historyCount={}, artifactCount={}",
                worker.getName(), context.getHistoryCount(), context.getSharedDataCount());

        return prompt.toString();
    }

    /**
     * 格式化共享数据（黑板）
     * @param sharedData 共享数据Map
     * @return 格式化后的文本
     */
    private String formatSharedData(Map<String, String> sharedData) {
        if (sharedData == null || sharedData.isEmpty()) {
            return "(无共享数据)";
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;

        for (Map.Entry<String, String> entry : sharedData.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // 长度限制：每个 value 最多显示 1000 字符
            String displayValue = value;
//            if (value.length() > 1000) {
//                displayValue = value.substring(0, 1000) + "\n[... 省略 " + (value.length() - 1000) + " 字符]";
//            }

            sb.append(index++).append(". **").append(key).append("**:\n");
            sb.append("```\n");
            sb.append(displayValue);
            sb.append("\n```\n\n");
        }

        return sb.toString();
    }

    /**
     * 格式化对话历史
     * @param recentHistory 最近的对话消息列表
     * @return 格式化后的文本
     */
    private String formatRecentHistory(List<Message> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return "(无历史记录)";
        }

        StringBuilder sb = new StringBuilder();
        int maxMessages = Math.min(recentHistory.size(), 3);

        for (int i = 0; i < maxMessages; i++) {
            Message msg = recentHistory.get(i);

            String role = msg.getRole();
            String content = msg.getContent();
            String agentName = msg.getAgentName();

            // 角色标识
            String roleDisplay = switch (role) {
                case "user" -> "用户";
                case "assistant" -> (agentName != null ? agentName : "助手");
                case "system" -> "系统";
                default -> role;
            };

            // 内容裁剪
            String displayContent = content;
//            if (content.length() > 500) {
//                displayContent = content.substring(0, 500) + "\n[... 省略 " + (content.length() - 500) + " 字符]";
//            }

            sb.append("[").append(roleDisplay).append("]: ");
            sb.append(displayContent);
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 组合系统Prompt和用户消息
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息(可选)
     * @return 组合后的完整Prompt
     */
    public String combinePrompts(String systemPrompt, String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n" + userMessage;
    }

    // ========== 辅助方法 ==========

    /**
     * 获取 Agent 的工具信息列表
     * @param agentId Agent ID
     * @return 工具信息列表
     */
    private List<ToolInfo> getAgentTools(String agentId) {
        try {
            Long id = Long.parseLong(agentId);
            List<String> toolIds = agentToolService.getEnabledToolIds(id);

            if (toolIds.isEmpty()) {
                return List.of();
            }

            // 从 McpToolRegistry 获取工具信息
            return toolIds.stream()
                .map(toolId -> mcpToolRegistry.listAvailableTools().stream()
                    .filter(tool -> tool.getId().equals(toolId))
                    .findFirst()
                    .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to get tools for agent: {}", agentId, e);
            return List.of();
        }
    }

    /**
     * 格式化工具列表为简洁文本（用于 Planner）
     * 按 MCP Server 分组展示：MCP名称[工具1(描述), 工具2(描述)]
     * 最多显示前5个工具，超过则显示数量
     */
    private String formatToolsForPlanner(List<ToolInfo> tools) {
        if (tools == null || tools.isEmpty()) {
            return "(无工具)";
        }

        // 按 MCP Server 分组
        Map<String, List<ToolInfo>> toolsByServer = tools.stream()
            .collect(Collectors.groupingBy(
                tool -> tool.getServerName() != null ? tool.getServerName() : "未知MCP",
                Collectors.toList()
            ));

        int maxToolsPerServer = 3; // 每个 MCP 最多显示3个工具
        StringBuilder sb = new StringBuilder();

        int serverCount = 0;
        for (Map.Entry<String, List<ToolInfo>> entry : toolsByServer.entrySet()) {
            if (serverCount > 0) {
                sb.append("; ");
            }

            String serverName = entry.getKey();
            List<ToolInfo> serverTools = entry.getValue();

            // 格式: MCP名称[tool1(描述), tool2(描述)]
            sb.append(serverName).append("[");

            List<ToolInfo> displayTools = serverTools.stream()
                .limit(maxToolsPerServer)
                .toList();

            String toolsText = displayTools.stream()
                .map(tool -> {
                    String desc = tool.getDescription();
                    // 描述最多20字符
//                    if (desc != null && desc.length() > 20) {
//                        desc = desc.substring(0, 17) + "...";
//                    }
                    return tool.getName() + "(" + (desc != null ? desc : "无描述") + ")";
                })
                .collect(Collectors.joining(", "));

            sb.append(toolsText);

            if (serverTools.size() > maxToolsPerServer) {
                sb.append(String.format(" +%d", serverTools.size() - maxToolsPerServer));
            }

            sb.append("]");
            serverCount++;
        }

        return sb.toString();
    }

    /**
     * 格式化 Worker 列表为文本（简化版）
     * 只包含 ID, name, description，不包含 systemPrompt 和工具列表
     */
    private String formatWorkerRoster(List<AgentProfile> workers) {
        if (workers == null || workers.isEmpty()) {
            return "(无可用Worker)";
        }

        StringBuilder sb = new StringBuilder();
        for (AgentProfile worker : workers) {
            String description = worker.getDescription();
            if (description == null || description.isBlank()) {
                description = "通用任务执行";
            }

            sb.append(String.format("- ID: %s, 名称: %s, 描述: %s\n",
                    worker.getId(),
                    worker.getName(),
                    description));
        }
        return sb.toString();
    }
}
