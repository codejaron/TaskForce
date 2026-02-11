package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 任务完成事件
 */
@Getter
public class TaskCompletedEvent extends OrchestrationEvent {

    private String taskId;
    private String owner;

    public TaskCompletedEvent() {
        super();
    }

    public TaskCompletedEvent(String sessionId, String taskId, String owner) {
        super(sessionId);
        this.taskId = taskId;
        this.owner = owner;
    }

    @Override
    public String getEventType() {
        return "task_completed";
    }
}
