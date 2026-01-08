package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 步骤开始事件
 */
@Getter
public class StepStartEvent extends OrchestrationEvent {

    private final String stepId;
    private final int stepIndex;
    private final String description;
    private final String assignedAgentId;
    private final String assignedAgentName;

    public StepStartEvent(String sessionId, String stepId, int stepIndex,
                          String description, String assignedAgentId, String assignedAgentName) {
        super(sessionId);
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.description = description;
        this.assignedAgentId = assignedAgentId;
        this.assignedAgentName = assignedAgentName;
    }

    @Override
    public String getEventType() {
        return "step_start";
    }
}
