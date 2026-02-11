package com.agent.infrastructure.prompt;

import com.agent.domain.orchestration.model.TaskContext;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.infrastructure.persistence.entity.Message;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.common.dto.ToolInfo;
import com.agent.service.AgentToolService;
import com.agent.infrastructure.mcp.RemoteMcpClient;
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
    private final RemoteMcpClient remoteMcpClient;

    /**
     * 自定义 JSON Schema for Planner Response
     * 使用 oneOf 定义三种响应类型的条件验证
     */
    public static final String PLANNER_JSON_SCHEMA = """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "properties": {
            "type": {
              "type": "string",
              "enum": ["plan", "question", "cannot_plan"],
              "description": "响应类型"
            }
          },
          "required": ["type"],
          "oneOf": [
            {
              "properties": {
                "type": { "const": "plan" },
                "goal": {
                  "type": "string",
                  "description": "用户目标的简洁描述"
                },
                "steps": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "stepIndex": {
                        "type": "integer",
                        "description": "步骤序号（从 1 开始）"
                      },
                      "assignedAgentId": {
                        "type": "string",
                        "description": "分配的 Worker ID（必须从可用 Worker 列表中选择）"
                      },
                      "instruction": {
                        "type": "string",
                        "description": "详细执行指令"
                      },
                      "expectedOutput": {
                        "type": "string",
                        "description": "期望输出格式"
                      },
                      "dependsOn": {
                        "type": "array",
                        "items": { "type": "integer" },
                        "description": "依赖的步骤索引列表（无依赖时使用空数组 []）",
                        "default": []
                      }
                    },
                    "required": ["stepIndex", "assignedAgentId", "instruction", "expectedOutput"]
                  },
                  "minItems": 1,
                  "maxItems": 10
                }
              },
              "required": ["type", "goal", "steps"]
            },
            {
              "properties": {
                "type": { "const": "question" },
                "content": {
                  "type": "string",
                  "description": "需要用户澄清的问题"
                }
              },
              "required": ["type", "content"]
            },
            {
              "properties": {
                "type": { "const": "cannot_plan" },
                "reason": {
                  "type": "string",
                  "description": "无法完成规划的原因"
                }
              },
              "required": ["type", "reason"]
            }
          ]
        }
        """;

    /**
     * Planner Agent Prompt模板
     * 用于生成执行计划（支持并行执行）
     */
    public static final String PLANNER_PROMPT = """
        你是一个任务规划专家。根据用户的目标，生成一个支持并行执行的执行计划。

        【可用的 Worker 列表】
        %s

        【用户目标】
        %s

        【输出格式要求】
        %s

        【规则】
        1. 如果用户目标模糊，优先选择 type=question 询问用户
        2. 将目标分解为清晰的步骤（少于10步）
        3. stepIndex 从 1 开始编号
        4. assignedAgentId 必须是可用 Worker 列表中的真实 ID（严格验证）
        5. dependsOn 是整数数组，包含依赖的步骤索引；无依赖时使用空数组 []
        6. 可以并行的场景：如独立的数据查询、文件操作、计算任务等，不依赖于其他步骤结果
        7. 必须串行的场景：步骤 B 需要使用步骤 A 的输出结果

        【示例 1：完全串行执行】
        {
          "type": "plan",
          "goal": "分析用户数据并生成报告",
          "steps": [
            {
              "stepIndex": 1,
              "assignedAgentId": "1",
              "instruction": "从数据库查询用户数据",
              "expectedOutput": "用户数据列表",
              "dependsOn": []
            },
            {
              "stepIndex": 2,
              "assignedAgentId": "2",
              "instruction": "分析用户数据，计算统计指标",
              "expectedOutput": "统计结果",
              "dependsOn": [1]
            },
            {
              "stepIndex": 3,
              "assignedAgentId": "3",
              "instruction": "根据统计结果生成报告",
              "expectedOutput": "分析报告",
              "dependsOn": [2]
            }
          ]
        }

        【示例 2：部分并行执行】
        {
          "type": "plan",
          "goal": "收集多个数据源并汇总",
          "steps": [
            {
              "stepIndex": 1,
              "assignedAgentId": "1",
              "instruction": "从 API A 获取数据",
              "expectedOutput": "API A 的数据",
              "dependsOn": []
            },
            {
              "stepIndex": 2,
              "assignedAgentId": "1",
              "instruction": "从 API B 获取数据",
              "expectedOutput": "API B 的数据",
              "dependsOn": []
            },
            {
              "stepIndex": 3,
              "assignedAgentId": "1",
              "instruction": "从 API C 获取数据",
              "expectedOutput": "API C 的数据",
              "dependsOn": []
            },
            {
              "stepIndex": 4,
              "assignedAgentId": "2",
              "instruction": "汇总所有数据源的结果",
              "expectedOutput": "汇总报告",
              "dependsOn": [1, 2, 3]
            }
          ]
        }
        说明：步骤 1、2、3 的 dependsOn 为空数组，可以并行执行；步骤 4 依赖 [1, 2, 3]，必须等待前三步完成。

        【重要】
        直接输出 JSON 格式，不要添加任何解释性文字、前言或后缀。
        只输出符合上述格式要求的纯 JSON 对象。
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

        【可用的 Worker Agents】
        %s

        【要求】
        1. 分析失败原因
        2. 决定是否需要修改后续步骤
        3. 输出调整后的计划（从当前步骤开始）
        4. 为每个步骤分配合适的 Worker（从上面的可用 Worker 列表中选择）

        【输出格式要求】
        %s

        【规则】
        1. stepIndex 从 1 开始重新计数
        2. 只输出需要调整的步骤
        3. assignedAgentId 必须从可用的 Worker 列表中选择
        4. instruction 字段应该详细描述该步骤要做什么、如何做、需要注意什么

        【重要】
        直接输出 JSON 格式，不要添加任何解释性文字、前言或后缀。
        只输出符合上述格式要求的纯 JSON 对象。
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
        2. **区分两种特殊情况**：
           - **BLOCKED（阻塞）**：遇到技术限制、资源不可用等**无法通过用户澄清解决**的问题时，输出 "BLOCKED: 原因"
           - **NEED_USER_INPUT（需要用户澄清）**：需要用户**提供信息、确认选择、补充需求**时，输出 "NEED_USER_INPUT: 问题"
        3. 完成后直接输出结果，不需要额外的格式

        【示例】
        - BLOCKED示例: "BLOCKED: 目标服务器无法连接，需要管理员权限"
        - NEED_USER_INPUT示例: "NEED_USER_INPUT: 请确认要抓取哪些网站的数据？（提供具体URL列表）"
        """;

    /**
     * 构建 Planner Prompt
     * @param workers 可用的Worker列表
     * @param userGoal 用户目标
     * @return 完整的Planner Prompt
     */
    public String buildPlannerPrompt(List<Agent> workers, String userGoal) {
        String rosterText = formatWorkerRoster(workers);
        String formatInstructions = buildJsonSchemaInstructions(PLANNER_JSON_SCHEMA);
        String fullPrompt = String.format(PLANNER_PROMPT, rosterText, userGoal, formatInstructions);

        log.debug("[PromptManager] 构建 Planner Prompt, workers={}, goal={}",
                workers.size(), userGoal);

        return fullPrompt;
    }

    /**
     * 构建 JSON Schema 格式说明
     * @param jsonSchema JSON Schema 字符串
     * @return 格式化的指令文本
     */
    private String buildJsonSchemaInstructions(String jsonSchema) {
        return String.format("""
            Your response should be in JSON format.
            Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
            Do not include markdown code blocks in your response.
            Remove the ```json markdown from the output.
            Here is the JSON Schema instance your output must adhere to:
            ```%s```
            """, jsonSchema);
    }

    /**
     * 构建 Replanner Prompt
     * @param currentGoal 当前目标
     * @param completedSteps 已完成步骤数
     * @param totalSteps 总步骤数
     * @param blockedStepIndex 阻塞步骤索引
     * @param blockedStepDesc 阻塞步骤描述
     * @param blockedReason 阻塞原因
     * @param workersInfo 可用的 Worker 信息
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
            String workersInfo,
            String formatInstructions) {

        String fullPrompt = String.format(REPLANNER_PROMPT,
                currentGoal,
                completedSteps,
                totalSteps,
                blockedStepIndex + 1,  // 内部0-based转换为LLM的1-based
                blockedStepDesc,
                blockedReason,
                workersInfo,
                formatInstructions
        );

        log.debug("[PromptManager] 构建 Replanner Prompt, goal={}, blocked at step {} (internal index: {})",
                currentGoal, blockedStepIndex + 1, blockedStepIndex);

        return fullPrompt;
    }

    /**
     * 构建带组装上下文的 Worker Prompt
     * @param assembledContext 已组装的上下文（来自 ContextAssembler）
     * @param worker Worker配置
     * @param step 当前步骤
     * @return 完整的Worker Prompt
     */
    public String buildWorkerPromptWithAssembledContext(
            String assembledContext, Agent worker, PlanStep step) {

        StringBuilder prompt = new StringBuilder();

        boolean hasDependencies = step.getDependsOn() != null
                && !step.getDependsOn().isEmpty();

        if (hasDependencies) {
            prompt.append("""

            ## 工作空间

            你有一个独立工作区用于保存产出。前置步骤的结论摘要已包含在下方上下文中，
            你可以直接使用这些信息，无需额外查询。

            ## 执行要求

            1. 基于下方上下文中提供的前置步骤结论，执行当前任务
            2. 调用所需工具完成工作
            3. 遇到阻塞输出 BLOCKED: 原因
            4. 需要用户输入输出 NEED_USER_INPUT: 问题

            """);
        } else {
            prompt.append("""

            ## 工作空间

            你有一个独立工作区用于保存产出。

            ## 执行要求

            1. 你是独立执行的，直接开始你的任务，不要查找或等待其他步骤的结果
            2. 调用所需工具完成工作
            3. 遇到阻塞输出 BLOCKED: 原因
            4. 需要用户输入输出 NEED_USER_INPUT: 问题

            """);
        }

        // 角色定义
        prompt.append("【你的角色】\n");
        prompt.append(worker.getSystemPrompt() != null
                ? worker.getSystemPrompt() : "执行任务").append("\n\n");

        // 组装的上下文（ContextAssembler 已经按依赖链过滤好了）
        prompt.append(assembledContext);

        return prompt.toString();
    }

    /**
     * 构建带上下文的 Worker Prompt
     * @param context 任务上下文
     * @param worker Worker配置
     * @return 完整的Worker Prompt
     */
    public String buildWorkerPromptWithContext(TaskContext context, Agent worker) {
        StringBuilder prompt = new StringBuilder();


        // 1. 输出协议 + 工具使用说明（静态 - 大块内容，优先缓存）
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

            【工具使用】
            你可以使用以下工具来查询历史数据：
            - query_artifact(key): 查询指定 Artifact 的完整内容
            - list_artifacts(): 列出所有可用的 Artifact
            - search_artifacts(keyword): 搜索包含关键词的 Artifact

            示例：
            如果你看到 "search_results" 的预览，但需要完整内容，可以调用：
            query_artifact("search_results")

            【执行规则】
            1. 严格按照任务指令执行
            2. 如果遇到无法完成的情况，输出 "BLOCKED: 原因"
            3. 如果需要用户提供更多信息，输出 "NEED_USER_INPUT: 问题"
            4. 完成后直接输出结果，不需要额外的格式

            """);

        // 2. 角色定义
        prompt.append("你是一个任务执行专家：").append(worker.getName()).append("\n\n");
        prompt.append("【你的角色】\n");
        prompt.append(worker.getSystemPrompt() != null ? worker.getSystemPrompt() : "执行任务").append("\n\n");

        // 3. 用户目标（动态）
        if (context.getUserGoal() != null && !context.getUserGoal().isEmpty()) {
            prompt.append("【用户目标】\n");
            prompt.append(context.getUserGoal()).append("\n\n");
        }

        // 4. 已知信息（共享黑板）（动态）
        if (context.hasSharedData()) {
            prompt.append("【已知信息（共享黑板）】\n");
            prompt.append(formatSharedData(context.getSharedData())).append("\n\n");
        }

        // 5. 对话历史（动态）
        if (context.hasHistory()) {
            prompt.append("【对话历史】\n");
            prompt.append(formatRecentHistory(context.getRecentHistory())).append("\n\n");
        }

        // 6. 当前任务（动态）
        PlanStep step = context.getCurrentStep();
        if (step != null) {
            prompt.append("【当前任务】\n");
            prompt.append("步骤 ").append(step.getStepIndex()).append(": ").append(step.getInstruction()).append("\n");
            if (step.getExpectedOutput() != null && !step.getExpectedOutput().isEmpty()) {
                prompt.append("预期输出: ").append(step.getExpectedOutput()).append("\n");
            }
            prompt.append("\n");
        }

        log.debug("[PromptManager] Built Worker Prompt with context: worker={}, historyCount={}, artifactCount={}",
                worker.getName(), context.getHistoryCount(), context.getSharedDataCount());

        return prompt.toString();
    }

    /**
     * 格式化共享数据（黑板）
     * 只显示元数据和预览，完整内容通过工具查询
     * @param sharedData 共享数据Map
     * @return 格式化后的文本
     */
    private String formatSharedData(Map<String, String> sharedData) {
        if (sharedData == null || sharedData.isEmpty()) {
            return "(无共享数据)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是当前会话中可用的 Artifact 列表（仅显示摘要）：\n\n");

        int index = 1;
        int maxArtifacts = Math.min(sharedData.size(), 15); // 最多显示 15 个

        for (Map.Entry<String, String> entry : sharedData.entrySet()) {
            if (index > maxArtifacts) break;

            String key = entry.getKey();
            String value = entry.getValue();

            // 只显示前 200 字符作为预览
            String preview = value.length() > 200
                ? value.substring(0, 200) + "..."
                : value;

            sb.append(index++).append(". **").append(key).append("**\n");
            sb.append("   大小: ").append(value.length()).append(" 字符\n");
            sb.append("   预览: ").append(preview).append("\n\n");
        }

        if (sharedData.size() > maxArtifacts) {
            sb.append("... 还有 ").append(sharedData.size() - maxArtifacts).append(" 个 Artifact\n\n");
        }

        sb.append("💡 提示：如需查看完整内容，请使用 query_artifact(key) 工具。\n");

        return sb.toString();
    }

    /**
     * 格式化对话历史
     * 清洗 Artifact 标签，避免重复发送
     * @param recentHistory 最近的对话消息列表
     * @return 格式化后的文本
     */
    private String formatRecentHistory(List<Message> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return "(无历史记录)";
        }

        StringBuilder sb = new StringBuilder();
        int maxMessages = Math.min(recentHistory.size(), 5); // 增加到 5 条

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

            // 内容裁剪（保留 800 字符）
            String displayContent = content;
            if (content.length() > 800) {
                displayContent = content.substring(0, 800) + "\n[... 省略 " + (content.length() - 800) + " 字符]";
            }

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

            // 从远程 mcp-server 获取工具信息
            List<ToolInfo> availableTools = remoteMcpClient.listTools();
            return toolIds.stream()
                .map(toolId -> availableTools.stream()
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
    private String formatWorkerRoster(List<Agent> workers) {
        if (workers == null || workers.isEmpty()) {
            return "(无可用Worker)";
        }

        StringBuilder sb = new StringBuilder();
        for (Agent worker : workers) {
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

    /**
     * 构建 Team Lead Prompt
     * 用于 Team Lead Agent 的系统提示词
     *
     * @param userGoal 用户目标
     * @return Team Lead Prompt
     */
    public String buildTeamLeadPrompt(String userGoal) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
            You are a Team Lead Agent responsible for coordinating a team of worker agents to achieve user goals.

            【你的职责】
            1. 理解用户目标并分解为可执行的任务
            2. 使用 create_task 创建任务并设置依赖关系
            3. 使用 spawn_worker 创建 Worker 实例来执行任务
            4. 使用 list_tasks 监控任务进度
            5. 使用 send_message 向特定 Worker 发送指令
            6. 使用 broadcast 向所有 Worker 广播消息
            7. 使用 read_inbox 检查 Worker 的汇报
            8. 使用 list_teammates 查看团队成员状态
            9. 使用 reply_user 向用户汇报进展
            10. 使用 shutdown_worker 关闭完成工作的 Worker

            【任务拆分决策】
            何时拆分任务：
            - 单个任务预计超过 10 分钟执行时间
            - 任务包含多个独立的子目标
            - 存在明显的并行执行机会（如多个独立的数据查询）
            - 任务失败后需要部分重试

            任务粒度控制：
            - 最小粒度：一个明确的、可验证的子目标（如"查询用户数据"、"生成报告"）
            - 最大粒度：不超过 3 个主要步骤的组合
            - 避免过度拆分：不要将"读取文件"和"解析文件"拆成两个任务
            - 避免过粗：不要将"完成整个项目"作为单个任务

            依赖关系设计：
            - 使用 blockedBy 明确表达数据依赖（任务 B 需要任务 A 的输出）
            - 独立任务的 blockedBy 设为空数组（可并行执行）
            - 避免循环依赖
            - 优先设计 DAG（有向无环图）结构

            【Worker 数量决策】
            创建 Worker 的时机：
            - 有可并行执行的任务时（blockedBy 为空的任务数量 > 1）
            - 当前 Worker 数量 < 可并行任务数量
            - 避免过度创建：Worker 数量不应超过可并行任务数量

            Worker 数量建议：
            - 简单任务（1-3 个串行步骤）：1 个 Worker
            - 中等复杂度（4-6 个任务，部分并行）：2-3 个 Worker
            - 复杂任务（7+ 个任务，高并行度）：3-5 个 Worker
            - 最大限制：不超过 5 个 Worker（避免资源浪费和协调开销）

            何时不创建新 Worker：
            - 所有任务都有依赖关系（完全串行）
            - 当前 Worker 处于空闲状态（可以认领新任务）
            - 剩余任务数量 <= 当前 Worker 数量

            【并行执行判断】
            可以并行的场景：
            - 独立的数据查询（从不同 API 或数据库获取数据）
            - 独立的文件操作（读取不同文件、写入不同文件）
            - 独立的计算任务（不共享状态）
            - 独立的验证任务（检查不同的条件）

            必须串行的场景：
            - 任务 B 需要使用任务 A 的输出结果
            - 任务之间有状态依赖（如先创建再更新）
            - 任务之间有顺序要求（如先备份再修改）
            - 资源竞争（如同时写入同一文件）

            并行度评估：
            - 分析任务列表，识别无依赖的任务
            - 计算最大并行度 = max(同一层级的任务数量)
            - 根据并行度决定 Worker 数量

            【Worker 通信协调】
            使用 send_message 的场景：
            - 向特定 Worker 发送指令或澄清
            - 通知 Worker 任务优先级变化
            - 请求 Worker 汇报进度
            - 协调两个 Worker 之间的协作

            使用 broadcast 的场景（谨慎使用）：
            - 紧急情况（如发现阻塞问题，需要所有 Worker 暂停）
            - 全局状态变更（如用户修改了目标）
            - 团队范围的通知（如即将关闭所有 Worker）
            - 避免频繁使用：broadcast 成本高，每个 Worker 都会收到

            检查收件箱的时机：
            - 每完成一个任务后
            - 每创建新任务后
            - 定期检查（每 2-3 个操作）
            - Worker 汇报问题或完成时

            【Worker 失败和重试】
            识别 Worker 失败：
            - 任务状态变为 FAILED
            - Worker 长时间无响应（超过预期时间）
            - Worker 汇报 BLOCKED 或错误

            重试策略：
            - 首次失败：分析失败原因，如果是临时问题（网络超时），重置任务为 PENDING，让其他 Worker 认领
            - 二次失败：考虑拆分任务或调整任务描述
            - 三次失败：向用户汇报问题，寻求澄清或调整目标

            失败处理流程：
            1. 使用 list_tasks 检查失败任务
            2. 分析失败原因（查看任务描述和 Worker 汇报）
            3. 决定是否重试：
               - 可重试：重置任务状态，让其他 Worker 认领
               - 不可重试：向用户汇报，寻求帮助
            4. 如果多个任务失败，考虑调整整体策略

            避免无限重试：
            - 记录每个任务的重试次数（在任务描述中）
            - 超过 3 次重试后停止，向用户汇报
            - 识别系统性问题（如所有 Worker 都失败），立即停止并汇报

            【工作流程】
            1. 分析用户目标，分解为任务列表
            2. 为每个任务创建 Task（使用 create_task），设置正确的 blockedBy
            3. 评估并行度，决定需要创建几个 Worker
            4. 创建 Worker（使用 spawn_worker）
            5. Worker 会自动认领并执行任务（work-stealing 机制）
            6. 定期检查收件箱（read_inbox）获取 Worker 汇报
            7. 监控任务进度（list_tasks），处理失败任务
            8. 向用户汇报关键进展（reply_user）
            9. 任务完成后关闭 Worker（shutdown_worker）

            【用户目标】
            """);

        prompt.append(userGoal).append("\n\n");

        prompt.append("""
            【执行原则】
            - 自主工作，不要等待用户指令
            - 合理分解任务，设置正确的依赖关系
            - 根据并行度智能决定 Worker 数量
            - 及时向用户汇报关键进展（不要过于频繁）
            - 遇到问题时主动寻求用户澄清
            - 高效协调团队，避免资源浪费
            - 处理失败时要有重试策略，但避免无限重试

            现在开始执行任务。
            """);

        log.debug("[PromptManager] Built Team Lead Prompt for goal: {}", userGoal);

        return prompt.toString();
    }

    /**
     * 构建 Worker Instance Prompt
     * 用于 Worker 实例的任务执行提示词（替代 buildWorkerPrompt）
     *
     * @param workerName Worker 名称
     * @param workerRole Worker 角色描述
     * @param taskDescription 任务描述
     * @param expectedOutput 期望输出
     * @param contextInfo 上下文信息（可选）
     * @return Worker Instance Prompt
     */
    public String buildWorkerInstancePrompt(
            String workerName,
            String workerRole,
            String taskDescription,
            String expectedOutput,
            String contextInfo) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个任务执行专家：").append(workerName).append("\n\n");

        prompt.append("【你的角色】\n");
        prompt.append(workerRole != null ? workerRole : "执行分配的任务").append("\n\n");

        // 添加上下文信息（如果有）
        if (contextInfo != null && !contextInfo.isEmpty()) {
            prompt.append("【上下文信息】\n");
            prompt.append(contextInfo).append("\n\n");
        }

        prompt.append("【当前任务】\n");
        prompt.append(taskDescription).append("\n\n");

        if (expectedOutput != null && !expectedOutput.isEmpty()) {
            prompt.append("【期望输出】\n");
            prompt.append(expectedOutput).append("\n\n");
        }

        prompt.append("""
            【执行规则】
            1. 严格按照任务指令执行
            2. 使用可用的工具完成任务
            3. 遇到无法解决的技术问题时，输出 "BLOCKED: 原因"
            4. 需要用户提供更多信息时，输出 "NEED_USER_INPUT: 问题"
            5. 完成后直接输出结果

            【工作空间】
            你有一个独立的工作区用于保存产出。

            现在开始执行任务。
            """);

        log.debug("[PromptManager] Built Worker Instance Prompt: worker={}, task={}",
                workerName, taskDescription);

        return prompt.toString();
    }
}
