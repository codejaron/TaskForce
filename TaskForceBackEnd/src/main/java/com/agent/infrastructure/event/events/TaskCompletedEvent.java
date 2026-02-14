package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 任务完成事件
 */
@Getter
public class TaskCompletedEvent extends OrchestrationEvent {

    private int taskId;
    private String owner;
    private String completionNote;

    public TaskCompletedEvent() {
        super();
    }

    public TaskCompletedEvent(String sessionId, int taskId, String owner, String completionNote) {
        super(sessionId);
        this.taskId = taskId;
        this.owner = owner;
        this.completionNote = completionNote;
    }

    @Override
    public String getEventType() {
        return "task_completed";
    }
}
