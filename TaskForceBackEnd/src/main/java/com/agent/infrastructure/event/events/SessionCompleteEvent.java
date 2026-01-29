package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 会话完成事件
 */
@Getter
public class SessionCompleteEvent extends OrchestrationEvent {

    private String reason;
    private int totalStepsExecuted;

    // 无参构造函数（Jackson 反序列化需要）
    public SessionCompleteEvent() {
        super();
    }

    public SessionCompleteEvent(String sessionId, String reason, int totalStepsExecuted) {
        super(sessionId);
        this.reason = reason;
        this.totalStepsExecuted = totalStepsExecuted;
    }

    public SessionCompleteEvent(String sessionId, String reason) {
        this(sessionId, reason, 0);
    }

    @Override
    public String getEventType() {
        return "session_complete";
    }
}
