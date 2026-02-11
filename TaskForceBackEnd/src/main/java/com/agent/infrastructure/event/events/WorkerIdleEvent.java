package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Worker 空闲事件
 */
@Getter
public class WorkerIdleEvent extends OrchestrationEvent {

    private String instanceId;
    private String name;

    public WorkerIdleEvent() {
        super();
    }

    public WorkerIdleEvent(String sessionId, String instanceId, String name) {
        super(sessionId);
        this.instanceId = instanceId;
        this.name = name;
    }

    @Override
    public String getEventType() {
        return "worker_idle";
    }
}
