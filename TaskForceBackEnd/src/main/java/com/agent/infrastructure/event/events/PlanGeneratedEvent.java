package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;

/**
 * 计划生成完成事件
 */
@Getter
public class PlanGeneratedEvent extends OrchestrationEvent {

    private final String planId;
    private final String goal;
    private final int totalSteps;
    private final String formattedPlan;  // 完整的格式化计划内容

    public PlanGeneratedEvent(String sessionId, String planId, String goal, int totalSteps, String formattedPlan) {
        super(sessionId);
        this.planId = planId;
        this.goal = goal;
        this.totalSteps = totalSteps;
        this.formattedPlan = formattedPlan;
    }

    // 兼容前端的字段名
    public int getStepCount() {
        return totalSteps;
    }

    @Override
    public String getEventType() {
        return "plan_generated";
    }
}
