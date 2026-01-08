package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 计划生成失败事件
 */
@Getter
public class PlanFailedEvent extends OrchestrationEvent {

    private final String reason;

    public PlanFailedEvent(String sessionId, String reason) {
        super(sessionId);
        this.reason = reason;
    }

    @Override
    public String getEventType() {
        return "plan_failed";
    }
}
