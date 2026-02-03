package com.agent.domain.context.listener;

import com.agent.domain.context.service.ContextService;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.infrastructure.event.events.PlanGeneratedEvent;
import com.agent.infrastructure.event.events.StepCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 步骤事件监听器
 * 监听步骤完成事件，检查并生成兜底 summary
 * 监听计划生成事件，保存 plan.md
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepEventListener {
    
    private final ContextService contextService;
    
    @EventListener
    public void onStepCompleted(StepCompletedEvent event) {
        try {
            // 从 stepId 提取步骤索引
            int stepIndex = event.getStepIndex();
            if (stepIndex > 0) {
                contextService.checkStepComplete(event.getSessionId(), stepIndex);
            }
        } catch (Exception e) {
            log.error("检查步骤完成失败: stepId={}", event.getStepId(), e);
        }
    }
    
    @EventListener
    public void onPlanGenerated(PlanGeneratedEvent event) {
        try {
            // 使用事件中的 formattedPlan
            String planMarkdown = generatePlanMarkdown(event);
            
            // 保存到上下文系统
            contextService.savePlan(event.getSessionId(), planMarkdown);
            
            log.info("计划已保存到上下文系统: sessionId={}, steps={}", 
                    event.getSessionId(), event.getTotalSteps());
        } catch (Exception e) {
            log.error("保存计划失败: sessionId={}", event.getSessionId(), e);
        }
    }

    
    /**
     * 生成计划 Markdown
     */
    private String generatePlanMarkdown(PlanGeneratedEvent event) {
        StringBuilder md = new StringBuilder();
        
        md.append("# 执行计划\n\n");
        md.append("**目标**: ").append(event.getGoal()).append("\n\n");
        md.append("**总步骤数**: ").append(event.getTotalSteps()).append("\n\n");
        md.append("**状态**: ").append(event.getStatus()).append("\n\n");
        
        md.append("## 步骤列表\n\n");
        md.append(event.getFormattedPlan());
        
        return md.toString();
    }
}
