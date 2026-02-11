package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 任务认领事件
 */
@Getter
public class TaskClaimedEvent extends OrchestrationEvent {

    private String taskId;
    private String owner;

    public TaskClaimedEvent() {
        super();
    }

    public TaskClaimedEvent(String sessionId, String taskId, String owner) {
        super(sessionId);
        this.taskId = taskId;
        this.owner = owner;
    }

    @Override
    public String getEventType() {
        return "task_claimed";
    }
}
