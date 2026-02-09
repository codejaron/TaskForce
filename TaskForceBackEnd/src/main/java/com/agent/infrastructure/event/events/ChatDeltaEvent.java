package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 单聊增量输出事件
 */
@Getter
public class ChatDeltaEvent extends OrchestrationEvent {

    private String delta;

    // 无参构造函数（Jackson 反序列化需要）
    public ChatDeltaEvent() {
        super();
    }

    public ChatDeltaEvent(String sessionId, String delta) {
        super(sessionId);
        this.delta = delta;
    }

    @Override
    public String getEventType() {
        return "chat_delta";
    }
}
