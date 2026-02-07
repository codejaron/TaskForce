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
 * 监听计划生成事件（计划现在存储在数据库中）
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
            // 计划现在存储在数据库中，不再需要保存到文件系统
            log.info("计划已生成: sessionId={}, steps={}",
                    event.getSessionId(), event.getTotalSteps());
        } catch (Exception e) {
            log.error("处理计划生成事件失败: sessionId={}", event.getSessionId(), e);
        }
    }
}
