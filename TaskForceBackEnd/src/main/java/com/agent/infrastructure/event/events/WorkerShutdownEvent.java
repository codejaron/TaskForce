package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Worker 关闭事件
 */
@Getter
public class WorkerShutdownEvent extends OrchestrationEvent {

    private String instanceId;
    private String name;
    private String reason;

    public WorkerShutdownEvent() {
        super();
    }

    public WorkerShutdownEvent(String sessionId, String instanceId, String name, String reason) {
        super(sessionId);
        this.instanceId = instanceId;
        this.name = name;
        this.reason = reason;
    }

    @Override
    public String getEventType() {
        return "worker_shutdown";
    }
}
