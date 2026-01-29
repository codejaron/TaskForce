package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 错误事件
 */
@Getter
public class ErrorEvent extends OrchestrationEvent {

    private String errorCode;
    private String errorMessage;

    // 无参构造函数（Jackson 反序列化需要）
    public ErrorEvent() {
        super();
    }

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
