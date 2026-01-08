package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 计划更新事件
 */
@Getter
public class PlanUpdatedEvent extends OrchestrationEvent {

    private final String planId;
    private final int newTotalSteps;
    private final int replanCount;

    public PlanUpdatedEvent(String sessionId, String planId, int newTotalSteps, int replanCount) {
        super(sessionId);
        this.planId = planId;
        this.newTotalSteps = newTotalSteps;
        this.replanCount = replanCount;
    }

    @Override
    public String getEventType() {
        return "plan_updated";
    }
}
