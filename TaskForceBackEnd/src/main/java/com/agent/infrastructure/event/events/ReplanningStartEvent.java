package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 重规划开始事件
 */
@Getter
public class ReplanningStartEvent extends OrchestrationEvent {

    private final String reason;

    public ReplanningStartEvent(String sessionId, String reason) {
        super(sessionId);
        this.reason = reason;
    }

    @Override
    public String getEventType() {
        return "replanning_start";
    }
}
