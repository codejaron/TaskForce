package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 单聊错误事件
 */
@Getter
public class ChatErrorEvent extends OrchestrationEvent {

    private String error;

    // 无参构造函数（Jackson 反序列化需要）
    public ChatErrorEvent() {
        super();
    }

    public ChatErrorEvent(String sessionId, String error) {
        super(sessionId);
        this.error = error;
    }

    @Override
    public String getEventType() {
        return "chat_error";
    }
}
