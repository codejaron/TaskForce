package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 会话暂停事件
 */
@Getter
public class SessionPauseEvent extends OrchestrationEvent {

    private final String reason;
    private final String pendingQuestion;

    public SessionPauseEvent(String sessionId, String reason, String pendingQuestion) {
        super(sessionId);
        this.reason = reason;
        this.pendingQuestion = pendingQuestion;
    }

    public SessionPauseEvent(String sessionId, String reason) {
        this(sessionId, reason, null);
    }

    @Override
    public String getEventType() {
        return "session_pause";
    }
}
