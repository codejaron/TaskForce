package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 步骤阻塞事件
 */
@Getter
public class StepBlockedEvent extends OrchestrationEvent {

    private final String stepId;
    private final int stepIndex;
    private final String blockedReason;

    public StepBlockedEvent(String sessionId, String stepId, int stepIndex, String blockedReason) {
        super(sessionId);
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.blockedReason = blockedReason;
    }

    @Override
    public String getEventType() {
        return "step_blocked";
    }
}
