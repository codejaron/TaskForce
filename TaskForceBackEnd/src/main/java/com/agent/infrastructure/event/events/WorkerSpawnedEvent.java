package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Worker 生成事件
 */
@Getter
public class WorkerSpawnedEvent extends OrchestrationEvent {

    private String instanceId;
    private String name;
    private String agentId;

    public WorkerSpawnedEvent() {
        super();
    }

    public WorkerSpawnedEvent(String sessionId, String instanceId, String name, String agentId) {
        super(sessionId);
        this.instanceId = instanceId;
        this.name = name;
        this.agentId = agentId;
    }

    @Override
    public String getEventType() {
        return "worker_spawned";
    }
}
