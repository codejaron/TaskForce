package com.agent.domain.context.assembly;

import com.agent.domain.context.model.StepContext;
import com.agent.domain.context.model.StepSummary;
import com.agent.domain.context.storage.WorkspaceStorage;
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
     * 组装完整上下文
     * @param sessionId 会话ID
     * @param currentStepIndex 当前步骤索引
     * @return 组装后的上下文 Markdown
     */
    public String assemble(String sessionId, int currentStepIndex) {
        StringBuilder context = new StringBuilder();
        
        // 1. 加载计划
        if (storage.exists(sessionId, "plan.md")) {
            String plan = storage.readFile(sessionId, "plan.md");
            context.append(renderPlan(plan, currentStepIndex));
        }
        
        // 2. 加载历史步骤索引
        List<StepContext> steps = loadSteps(sessionId, currentStepIndex);
        if (!steps.isEmpty()) {
            context.append("\n【历史步骤】\n\n");
            for (StepContext step : steps) {
                context.append(renderStepIndex(step));
            }
        }
        
        // 3. 最近一步完整输出（可选）
        if (config.isIncludeRecentOutput() && currentStepIndex > 1 && !steps.isEmpty()) {
            StepContext lastStep = steps.get(steps.size() - 1);
            String outputPath = String.format("step_%03d/output.md", lastStep.getStepIndex());
            if (storage.exists(sessionId, outputPath)) {
                String output = storage.readFile(sessionId, outputPath);
                context.append(renderRecentOutput(output));
            }
        }
        
        // 4. 当前步骤 + 目标复述
        context.append(renderCurrentStep(currentStepIndex));
        
        // 5. 资源提示
        context.append(renderResourceHint());
        
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
     * 渲染计划
     */
    private String renderPlan(String plan, int currentStepIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("【执行计划】\n\n");
        sb.append(plan);
        sb.append("\n\n当前进度：").append(currentStepIndex).append("\n\n");
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
    private String renderCurrentStep(int stepIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前步骤】\n");
        sb.append("## Step ").append(stepIndex).append("\n\n");
        sb.append("请执行当前步骤，完成后调用 write_step_summary 工具记录核心结论。\n\n");
        return sb.toString();
    }
    
    /**
     * 渲染资源提示
     */
    private String renderResourceHint() {
        return """
                【查阅详情】
                如需查看某步骤的详细数据，调用 read_file(path) 读取对应文件。
                
                """;
    }
}
