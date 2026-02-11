package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 任务解锁事件
 */
@Getter
public class TaskUnblockedEvent extends OrchestrationEvent {

    private String taskId;
    private String unblockedBy;

    public TaskUnblockedEvent() {
        super();
    }

    public TaskUnblockedEvent(String sessionId, String taskId, String unblockedBy) {
        super(sessionId);
        this.taskId = taskId;
        this.unblockedBy = unblockedBy;
    }

    @Override
    public String getEventType() {
        return "task_unblocked";
    }
}
