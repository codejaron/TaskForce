package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 会话恢复事件（人工回答后继续执行）
 */
@Getter
public class SessionResumeEvent extends OrchestrationEvent {

    private String userAnswer;

    public SessionResumeEvent() {
        super();
    }

    public SessionResumeEvent(String sessionId, String userAnswer) {
        super(sessionId);
        this.userAnswer = userAnswer;
    }

    @Override
    public String getEventType() {
        return "session_resume";
    }
}
