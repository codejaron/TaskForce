package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Worker 任务开始事件
 */
@Getter
public class WorkerTaskStartedEvent extends OrchestrationEvent {

    private String instanceId;
    private String taskId;
    private String taskSubject;

    public WorkerTaskStartedEvent() {
        super();
    }

    public WorkerTaskStartedEvent(String sessionId, String instanceId, String taskId, String taskSubject) {
        super(sessionId);
        this.instanceId = instanceId;
        this.taskId = taskId;
        this.taskSubject = taskSubject;
    }

    @Override
    public String getEventType() {
        return "worker_task_started";
    }
}
