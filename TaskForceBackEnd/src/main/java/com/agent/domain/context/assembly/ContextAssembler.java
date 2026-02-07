package com.agent.domain.context.assembly;

import com.agent.domain.context.model.StepContext;
import com.agent.domain.context.model.StepSummary;
import com.agent.domain.context.storage.WorkspaceStorage;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
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
    
    /**
     * 组装完整上下文（使用 ExecutionPlan 对象）
     * @param plan 执行计划对象
     * @param currentStepIndex 当前步骤索引
     * @return 组装后的上下文 Markdown
     */
    public String assemble(ExecutionPlan plan, int currentStepIndex) {
        StringBuilder context = new StringBuilder();

        // 1. 渲染计划（从 ExecutionPlan 对象获取）
        context.append(renderPlanFromObject(plan, currentStepIndex));

        // 2. 加载历史步骤索引（只加载依赖链上的步骤）
        List<StepContext> steps = loadSteps(plan, currentStepIndex);
        if (!steps.isEmpty()) {
            context.append("\n【历史步骤】\n\n");
            for (StepContext step : steps) {
                context.append(renderStepIndex(step));
            }
        }

        // 3. 直接依赖步骤的输出（优先用 summary，fallback 到 output）
        if (config.isIncludeRecentOutput() && currentStepIndex < plan.getSteps().size()) {
            PlanStep currentStep = plan.getSteps().get(currentStepIndex);
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
        PlanStep currentStep = plan.getSteps().get(currentStepIndex);
        context.append(renderCurrentStep(currentStepIndex, currentStep.getInstruction()));

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

        // 渲染步骤列表
        if (plan.getSteps() != null && !plan.getSteps().isEmpty()) {
            sb.append("**步骤：**\n");
            for (int i = 0; i < plan.getSteps().size(); i++) {
                PlanStep step = plan.getSteps().get(i);

                // 判断是否应该显示详细信息
                boolean shouldShowDetails = visibleSteps.contains(i);

                sb.append(i + 1).append(". ");

                // 标记当前步骤
                if (i == currentStepIndex) {
                    sb.append("**[当前]** ");
                } else if (i < currentStepIndex) {
                    sb.append("✓ ");
                }

                // 如果在可见范围内，显示详细指令；否则只显示占位符
                if (shouldShowDetails) {
                    sb.append(step.getInstruction());
                    if (step.getAssignedAgentName() != null) {
                        sb.append(" (").append(step.getAssignedAgentName()).append(")");
                    }
                } else {
                    sb.append("[并行步骤，暂不可见]");
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
        sb.append("## Step ").append(step.getStepIndex());
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
            sb.append("【依赖步骤 ").append(stepIndex).append(" 的结论摘要】\n\n");
            sb.append(content);
        } else {
            sb.append("【依赖步骤 ").append(stepIndex).append(" 的输出（截断）】\n\n");
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
        sb.append("## Step ").append(stepIndex).append("\n\n");
        sb.append(instruction).append("\n\n");
        sb.append("请执行当前步骤，完成后调用 write_step_summary 工具记录核心结论。\n\n");
        return sb.toString();
    }
    

}
