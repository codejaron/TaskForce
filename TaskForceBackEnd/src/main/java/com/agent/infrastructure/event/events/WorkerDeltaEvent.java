package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Worker 增量输出事件
 */
@Getter
public class WorkerDeltaEvent extends OrchestrationEvent {

    private final String stepId;
    private final String delta;

    public WorkerDeltaEvent(String sessionId, String stepId, String delta) {
        super(sessionId);
        this.stepId = stepId;
        this.delta = delta;
    }

    @Override
    public String getEventType() {
        return "worker_delta";
    }
}
