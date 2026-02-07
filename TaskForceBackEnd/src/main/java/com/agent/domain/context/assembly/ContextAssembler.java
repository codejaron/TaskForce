package com.agent.domain.context.assembly;

import com.agent.domain.context.model.StepContext;
import com.agent.domain.context.model.StepSummary;
import com.agent.domain.context.storage.WorkspaceStorage;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

        // 2. 加载历史步骤索引
        List<StepContext> steps = loadSteps(plan.getSessionId(), currentStepIndex);
        if (!steps.isEmpty()) {
            context.append("\n【历史步骤】\n\n");
            for (StepContext step : steps) {
                context.append(renderStepIndex(step));
            }
        }

        // 3. 最近一步完整输出（可选）
        if (config.isIncludeRecentOutput() && currentStepIndex > 0 && !steps.isEmpty()) {
            StepContext lastStep = steps.get(steps.size() - 1);
            String outputPath = String.format("step_%03d/output.md", lastStep.getStepIndex());
            if (storage.exists(plan.getSessionId(), outputPath)) {
                String output = storage.readFile(plan.getSessionId(), outputPath);
                context.append(renderRecentOutput(output));
            }
        }

        // 4. 当前步骤 + 目标复述
        PlanStep currentStep = plan.getSteps().get(currentStepIndex);
        context.append(renderCurrentStep(currentStepIndex, currentStep.getInstruction()));

        return context.toString();
    }
    
    /**
     * 加载历史步骤
     */
    private List<StepContext> loadSteps(String sessionId, int currentStepIndex) {
        List<StepContext> steps = new ArrayList<>();
        
        // 从 step_001 开始，到 currentStepIndex - 1
        int startIndex = Math.max(1, currentStepIndex - config.getMaxHistorySteps());
        for (int i = startIndex; i < currentStepIndex; i++) {
            StepContext step = loadStep(sessionId, i);
            if (step != null) {
                steps.add(step);
            }
        }
        
        return steps;
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
     */
    private String renderPlanFromObject(ExecutionPlan plan, int currentStepIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("【执行计划】\n\n");
        sb.append("**目标：** ").append(plan.getGoal()).append("\n\n");

        // 渲染步骤列表
        if (plan.getSteps() != null && !plan.getSteps().isEmpty()) {
            sb.append("**步骤：**\n");
            for (int i = 0; i < plan.getSteps().size(); i++) {
                PlanStep step = plan.getSteps().get(i);
                sb.append(i + 1).append(". ");

                // 标记当前步骤
                if (i == currentStepIndex) {
                    sb.append("**[当前]** ");
                } else if (i < currentStepIndex) {
                    sb.append("✓ ");
                }

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
     * 渲染最近输出
     */
    private String renderRecentOutput(String output) {
        StringBuilder sb = new StringBuilder();
        sb.append("【上一步完整输出】\n\n");
        sb.append(output);
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
