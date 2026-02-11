package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 任务创建事件
 */
@Getter
public class TaskCreatedEvent extends OrchestrationEvent {

    private int taskId;
    private String subject;
    private String description;

    public TaskCreatedEvent() {
        super();
    }

    public TaskCreatedEvent(String sessionId, int taskId, String subject, String description) {
        super(sessionId);
        this.taskId = taskId;
        this.subject = subject;
        this.description = description;
    }

    @Override
    public String getEventType() {
        return "task_created";
    }
}
