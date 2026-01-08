package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 步骤完成事件
 */
@Getter
public class StepCompletedEvent extends OrchestrationEvent {

    private final String stepId;
    private final int stepIndex;
    private final String result;

    public StepCompletedEvent(String sessionId, String stepId, int stepIndex, String result) {
        super(sessionId);
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.result = result;
    }

    @Override
    public String getEventType() {
        return "step_completed";
    }
}
