package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 会话暂停事件
 */
@Getter
public class SessionPauseEvent extends OrchestrationEvent {

    private String reason;
    private String pendingQuestion;

    // 无参构造函数（Jackson 反序列化需要）
    public SessionPauseEvent() {
        super();
    }

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
