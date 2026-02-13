package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 任务失败事件
 */
@Getter
public class TaskFailedEvent extends OrchestrationEvent {

    private int taskId;
    private String owner;

    public TaskFailedEvent() {
        super();
    }

    public TaskFailedEvent(String sessionId, int taskId, String owner) {
        super(sessionId);
        this.taskId = taskId;
        this.owner = owner;
    }

    @Override
    public String getEventType() {
        return "task_failed";
    }
}
