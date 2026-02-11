package com.agent.domain.context.assembly;

import com.agent.domain.context.model.StepContext;
import com.agent.domain.context.model.StepSummary;
import com.agent.domain.context.storage.WorkspaceStorage;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.model.StepStatus;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.model.Team;
import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 上下文组装器
 * 负责将历史步骤、计划、当前任务组装成完整上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextAssembler {

    private final WorkspaceStorage storage;
    private final ContextConfig config;
    private final TaskBoardService taskBoardService;
    private final InboxService inboxService;
    private final TeamService teamService;
    
    /**
     * 组装完整上下文（使用 ExecutionPlan 对象）
     * @param plan 执行计划对象
     * @param currentStepIndex 当前步骤索引（从1开始）
     * @return 组装后的上下文 Markdown
     */
    public String assemble(ExecutionPlan plan, int currentStepIndex) {
        StringBuilder context = new StringBuilder();

        // 将 stepIndex（从1开始）转换为数组索引（从0开始）
        int arrayIndex = currentStepIndex - 1;

        // 1. 渲染计划（从 ExecutionPlan 对象获取）
        context.append(renderPlanFromObject(plan, arrayIndex));

        // 2. 加载历史步骤索引（只加载依赖链上的步骤）
        List<StepContext> steps = loadSteps(plan, arrayIndex);
        if (!steps.isEmpty()) {
            context.append("\n【历史步骤】\n\n");
            for (StepContext step : steps) {
                context.append(renderStepIndex(step));
            }
        }

        // 3. 直接依赖步骤的输出（优先用 summary，fallback 到 output）
        if (config.isIncludeRecentOutput() && arrayIndex < plan.getSteps().size()) {
            PlanStep currentStep = plan.getSteps().get(arrayIndex);
            if (currentStep.getDependsOn() != null && !currentStep.getDependsOn().isEmpty()) {
                Map<String, Integer> stepIdToIndex = new HashMap<>();
                for (int i = 0; i < plan.getSteps().size(); i++) {
                    stepIdToIndex.put(plan.getSteps().get(i).getStepId(), i);
                }

                for (String depStepId : currentStep.getDependsOn()) {
                    Integer depStepIndex = stepIdToIndex.get(depStepId);
                    if (depStepIndex != null) {
                        // 优先读 summary
                        String summaryPath = String.format("step_%03d/summary.md", depStepIndex);
                        if (storage.exists(plan.getSessionId(), summaryPath)) {
                            String summary = storage.readFile(plan.getSessionId(), summaryPath);
                            context.append(renderDependencyOutput(depStepIndex, summary, true));
                        } else {
                            // fallback: 读 output 但截断
                            String outputPath = String.format("step_%03d/output.md", depStepIndex);
                            if (storage.exists(plan.getSessionId(), outputPath)) {
                                String output = storage.readFile(plan.getSessionId(), outputPath);
                                context.append(renderDependencyOutput(depStepIndex, output, false));
                            }
                        }
                    }
                }
            }
        }


        // 4. 当前步骤 + 目标复述
        PlanStep currentStep = plan.getSteps().get(arrayIndex);
        context.append(renderCurrentStep(arrayIndex, currentStep.getInstruction()));

        return context.toString();
    }
    
    /**
     * 加载历史步骤（只加载 dependsOn 链上的步骤）
     */
    private List<StepContext> loadSteps(ExecutionPlan plan, int currentStepIndex) {
        List<StepContext> steps = new ArrayList<>();

        // 收集依赖链上的所有步骤索引
        Set<Integer> dependencyIndices = collectDependencyChain(plan, currentStepIndex);

        // 按索引排序并加载
        List<Integer> sortedIndices = new ArrayList<>(dependencyIndices);
        Collections.sort(sortedIndices);

        for (int i : sortedIndices) {
            StepContext step = loadStep(plan.getSessionId(), i);
            if (step != null) {
                steps.add(step);
            }
        }

        return steps;
    }

    /**
     * 收集当前步骤的所有依赖步骤索引（递归）
     */
    private Set<Integer> collectDependencyChain(ExecutionPlan plan, int currentStepIndex) {
        Set<Integer> dependencies = new HashSet<>();
        Set<String> visited = new HashSet<>();

        // 建立 stepId -> stepIndex 的映射
        Map<String, Integer> stepIdToIndex = new HashMap<>();
        for (int i = 0; i < plan.getSteps().size(); i++) {
            PlanStep step = plan.getSteps().get(i);
            stepIdToIndex.put(step.getStepId(), i);
        }

        // 递归收集依赖
        collectDependenciesRecursive(plan, currentStepIndex, stepIdToIndex, dependencies, visited);

        return dependencies;
    }

    /**
     * 递归收集依赖步骤
     */
    private void collectDependenciesRecursive(
            ExecutionPlan plan,
            int stepIndex,
            Map<String, Integer> stepIdToIndex,
            Set<Integer> dependencies,
            Set<String> visited) {

        if (stepIndex < 0 || stepIndex >= plan.getSteps().size()) {
            return;
        }

        PlanStep step = plan.getSteps().get(stepIndex);

        // 避免循环依赖
        if (visited.contains(step.getStepId())) {
            return;
        }
        visited.add(step.getStepId());

        // 如果有依赖，递归处理
        if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
            for (String depStepId : step.getDependsOn()) {
                Integer depStepIndex = stepIdToIndex.get(depStepId);
                if (depStepIndex != null) {
                    dependencies.add(depStepIndex);
                    collectDependenciesRecursive(plan, depStepIndex, stepIdToIndex, dependencies, visited);
                }
            }
        }
    }
    
    /**
     * 加载单个步骤
     */
    private StepContext loadStep(String sessionId, int stepIndex) {
        String stepDir = String.format("step_%03d", stepIndex);
        String summaryPath = stepDir + "/summary.md";
        
        // 检查步骤目录是否存在
        if (!storage.exists(sessionId, summaryPath)) {
            return null;
        }
        
        StepContext step = StepContext.builder()
                .stepIndex(stepIndex)
                .summaryPath(summaryPath)
                .outputPath(stepDir + "/output.md")
                .build();
        
        // 解析 summary.md
        String summaryContent = storage.readFile(sessionId, summaryPath);
        StepSummary summary = StepSummary.parse(summaryContent);
        step.setStepTitle(summary.getStepTitle());
        step.setConclusion(summary.getConclusion());
        step.setFindings(summary.getFindings());
        step.setNextSuggestion(summary.getNextSuggestion());
        
        // 列出工具文件
        String toolsDir = stepDir + "/tools";
        List<String> toolFiles = storage.listFiles(sessionId, toolsDir);
        step.setToolFiles(toolFiles.stream()
                .map(f -> toolsDir + "/" + f)
                .toList());
        
        return step;
    }
    
    /**
     * 渲染计划（从 ExecutionPlan 对象）
     * 对同层步骤做信息屏蔽，避免并行节点看到彼此的指令
     */
    private String renderPlanFromObject(ExecutionPlan plan, int currentStepIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("【执行计划】\n\n");
        sb.append("**目标：** ").append(plan.getGoal()).append("\n\n");

        // 收集当前步骤的依赖链（只有依赖链上的步骤才可见）
        Set<Integer> visibleSteps = collectDependencyChain(plan, currentStepIndex);
        visibleSteps.add(currentStepIndex); // 当前步骤也可见

        // 只渲染可见的步骤，不显示其他步骤
        if (!visibleSteps.isEmpty()) {
            sb.append("**相关步骤：**\n");

            // 按索引排序，保持步骤顺序
            List<Integer> sortedVisibleSteps = new ArrayList<>(visibleSteps);
            Collections.sort(sortedVisibleSteps);

            for (int i : sortedVisibleSteps) {
                PlanStep step = plan.getSteps().get(i);
                sb.append(i + 1).append(". ");

                // 标记当前步骤
                if (i == currentStepIndex) {
                    sb.append("**[当前]** ");
                } else {
                    // 使用实际的status字段判断完成状态
                    if (step.getStatus() == StepStatus.DONE) {
                        sb.append("✓ ");
                    } else if (step.getStatus() == StepStatus.IN_PROGRESS) {
                        sb.append("⏳ ");
                    }
                }

                // 显示步骤指令
                sb.append(step.getInstruction());
                if (step.getAssignedAgentName() != null) {
                    sb.append(" (").append(step.getAssignedAgentName()).append(")");
                }
                sb.append("\n");
            }
        }

        sb.append("\n当前进度：").append(currentStepIndex + 1).append("/").append(plan.getSteps().size()).append("\n\n");
        return sb.toString();
    }
    
    /**
     * 渲染步骤索引
     */
    private String renderStepIndex(StepContext step) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Step ").append(step.getStepIndex() + 1);
        if (step.getStepTitle() != null) {
            sb.append(": ").append(step.getStepTitle());
        }
        sb.append("\n");
        
        // 文件列表
        if (!step.getToolFiles().isEmpty()) {
            for (String file : step.getToolFiles()) {
                sb.append("📁 ").append(file).append("\n");
            }
            sb.append("\n");
        }
        
        // 摘要（从 summary.md 提取）
        if (step.hasSummary()) {
            sb.append("> ").append(step.getConclusion()).append("\n");
            
            if (step.getFindings() != null && !step.getFindings().isEmpty()) {
                sb.append("> 💡 ").append(String.join(", ", step.getFindings())).append("\n");
            }
            
            if (step.getNextSuggestion() != null) {
                sb.append("> → ").append(step.getNextSuggestion()).append("\n");
            }
        }
        
        sb.append("\n---\n\n");
        return sb.toString();
    }
    
    /**
     * 渲染依赖步骤的输出
     */
    private String renderDependencyOutput(int stepIndex, String content, boolean isSummary) {
        StringBuilder sb = new StringBuilder();
        if (isSummary) {
            sb.append("【依赖步骤 ").append(stepIndex + 1).append(" 的结论摘要】\n\n");
            sb.append(content);
        } else {
            sb.append("【依赖步骤 ").append(stepIndex+1).append(" 的输出（截断）】\n\n");
            // 截断保护，避免单个依赖占用太多 token
            if (content.length() > 2000) {
                sb.append(content, 0, 2000);
                sb.append("\n\n... [输出过长，已截断。建议该步骤补充 summary]");
            } else {
                sb.append(content);
            }
        }
        sb.append("\n\n---\n\n");
        return sb.toString();
    }


    /**
     * 渲染当前步骤
     */
    private String renderCurrentStep(int stepIndex, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前步骤】\n");
        sb.append("## Step ").append(stepIndex + 1).append("\n\n");
        sb.append(instruction).append("\n\n");
        sb.append("请执行当前步骤，完成后调用 write_step_summary 工具记录核心结论。\n\n");
        return sb.toString();
    }

    /**
     * 组装任务上下文（基于 Task Board 模型）
     * @param sessionId 会话 ID
     * @param taskId 任务 ID
     * @return 组装后的上下文 Markdown
     */
    public String assembleForTask(String sessionId, int taskId) {
        StringBuilder context = new StringBuilder();

        // 1. 获取当前任务
        Task currentTask = taskBoardService.getTask(sessionId, taskId);

        // 2. 渲染任务板概览
        context.append(renderTaskBoard(sessionId, taskId));

        // 3. 收集并渲染依赖链上的任务输出
        List<Task> dependencyChain = collectTaskDependencyChain(sessionId, taskId);
        if (!dependencyChain.isEmpty()) {
            context.append("\n【依赖任务输出】\n\n");
            for (Task depTask : dependencyChain) {
                context.append(renderTaskOutput(sessionId, depTask));
            }
        }

        // 4. 渲染团队成员信息
        try {
            String teammatesContent = renderTeammates(sessionId);
            if (teammatesContent != null && !teammatesContent.isEmpty()) {
                context.append(teammatesContent);
            }
        } catch (Exception e) {
            log.debug("Team info not available: {}", e.getMessage());
        }

        // 6. 渲染当前任务
        context.append(renderCurrentTask(currentTask));

        return context.toString();
    }

    /**
     * 渲染任务板概览
     */
    private String renderTaskBoard(String sessionId, int currentTaskId) {
        StringBuilder sb = new StringBuilder();
        sb.append("【任务板】\n\n");

        List<Task> allTasks = taskBoardService.listTasks(sessionId);
        if (allTasks.isEmpty()) {
            return "";
        }

        // 按 taskId 排序
        allTasks.sort(Comparator.comparing(Task::getTaskId));

        sb.append("**所有任务：**\n");
        for (Task task : allTasks) {
            sb.append("- ");

            // 标记当前任务
            if (task.getTaskId() == currentTaskId) {
                sb.append("**[当前]** ");
            }

            // 状态标记
            switch (task.getStatus()) {
                case COMPLETED:
                    sb.append("✓ ");
                    break;
                case IN_PROGRESS:
                    sb.append("⏳ ");
                    break;
                case CLAIMED:
                    sb.append("🔒 ");
                    break;
                case FAILED:
                    sb.append("❌ ");
                    break;
                case PENDING:
                    sb.append("⏸ ");
                    break;
            }

            sb.append(task.getSubject());

            // 显示所有者
            if (task.getOwner() != null) {
                sb.append(" (").append(task.getOwner()).append(")");
            }

            // 显示阻塞信息
            if (task.getBlockedBy() != null && !task.getBlockedBy().isEmpty()) {
                String blockedByStr = task.getBlockedBy().stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
                sb.append(" [blocked by: ").append(blockedByStr).append("]");
            }

            sb.append("\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 收集任务依赖链（递归）
     * 返回按依赖顺序排序的任务列表（被依赖的任务在前）
     */
    private List<Task> collectTaskDependencyChain(String sessionId, int taskId) {
        Set<Integer> visited = new HashSet<>();
        List<Task> dependencyChain = new ArrayList<>();

        collectTaskDependenciesRecursive(sessionId, taskId, visited, dependencyChain);

        // 反转列表，使被依赖的任务在前
        Collections.reverse(dependencyChain);

        return dependencyChain;
    }

    /**
     * 递归收集任务依赖
     */
    private void collectTaskDependenciesRecursive(String sessionId, int taskId, Set<Integer> visited, List<Task> result) {
        if (visited.contains(taskId)) {
            return;
        }
        visited.add(taskId);

        try {
            Task task = taskBoardService.getTask(sessionId, taskId);

            // 先递归处理依赖
            if (task.getBlockedBy() != null && !task.getBlockedBy().isEmpty()) {
                for (int depTaskId : task.getBlockedBy()) {
                    collectTaskDependenciesRecursive(sessionId, depTaskId, visited, result);
                }
            }

            // 只添加已完成的任务
            if (task.isCompleted()) {
                result.add(task);
            }
        } catch (Exception e) {
            log.warn("Failed to load task: {}", taskId, e);
        }
    }

    /**
     * 渲染任务输出
     */
    private String renderTaskOutput(String sessionId, Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Task: ").append(task.getSubject()).append("\n");
        sb.append("**Owner:** ").append(task.getOwner() != null ? task.getOwner() : "N/A").append("\n");
        sb.append("**Status:** ").append(task.getStatus()).append("\n\n");

        // 尝试读取任务输出（假设存储在 task_{taskId}/output.md）
        String outputPath = String.format("task_%d/output.md", task.getTaskId());
        if (storage.exists(sessionId, outputPath)) {
            String output = storage.readFile(sessionId, outputPath);
            if (output != null && !output.isEmpty()) {
                // 截断保护
                if (output.length() > 2000) {
                    sb.append(output, 0, 2000);
                    sb.append("\n\n... [输出过长，已截断]");
                } else {
                    sb.append(output);
                }
            }
        } else {
            sb.append("_（无输出记录）_");
        }

        sb.append("\n\n---\n\n");
        return sb.toString();
    }

    /**
     * 渲染收件箱消息
     */
    private String renderInbox(String sessionId, String instanceId) {
        try {
            List<TeamMessage> messages = inboxService.readInbox(instanceId);
            if (messages == null || messages.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【收件箱】\n\n");
            sb.append("你有 ").append(messages.size()).append(" 条新消息：\n\n");

            for (TeamMessage msg : messages) {
                sb.append("**From:** ").append(msg.getFrom()).append("\n");
                sb.append("**Type:** ").append(msg.getType()).append("\n");
                sb.append("**Content:**\n");
                sb.append(msg.getText()).append("\n\n");
                sb.append("---\n\n");
            }

            return sb.toString();
        } catch (UnsupportedOperationException e) {
            // InboxService 尚未实现
            return "";
        }
    }

    /**
     * 渲染团队成员信息
     */
    private String renderTeammates(String sessionId) {
        try {
            Team team = teamService.getTeamBySessionId(sessionId);
            if (team == null || team.getMembers() == null || team.getMembers().isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【团队成员】\n\n");

            for (var member : team.getMembers()) {
                sb.append("- ").append(member.getName());
                sb.append(" (").append(member.getRole()).append(")");
                sb.append(" - ").append(member.getStatus());
                sb.append("\n");
            }

            sb.append("\n");
            return sb.toString();
        } catch (UnsupportedOperationException e) {
            // TeamService 尚未实现
            return "";
        }
    }

    /**
     * 渲染当前任务
     */
    private String renderCurrentTask(Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前任务】\n");
        sb.append("## ").append(task.getSubject()).append("\n\n");
        sb.append("**描述：**\n");
        sb.append(task.getDescription()).append("\n\n");
        sb.append("**状态：** ").append(task.getStatus()).append("\n");
        if (task.getOwner() != null) {
            sb.append("**所有者：** ").append(task.getOwner()).append("\n");
        }
        sb.append("\n");
        sb.append("请执行当前任务，完成后更新任务状态。\n\n");
        return sb.toString();
    }


}
