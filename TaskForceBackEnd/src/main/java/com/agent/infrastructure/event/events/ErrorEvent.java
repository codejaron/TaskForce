package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 错误事件
 */
@Getter
public class ErrorEvent extends OrchestrationEvent {

    private final String errorCode;
    private final String errorMessage;

    public ErrorEvent(String sessionId, String errorCode, String errorMessage) {
        super(sessionId);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public ErrorEvent(String sessionId, String errorMessage) {
        this(sessionId, "UNKNOWN", errorMessage);
    }

    @Override
    public String getEventType() {
        return "error";
    }
}
