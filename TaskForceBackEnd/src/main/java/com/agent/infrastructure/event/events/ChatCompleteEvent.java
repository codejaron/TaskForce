package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 单聊完成事件
 */
@Getter
public class ChatCompleteEvent extends OrchestrationEvent {

    private String status;

    // 无参构造函数（Jackson 反序列化需要）
    public ChatCompleteEvent() {
        super();
    }

    public ChatCompleteEvent(String sessionId) {
        super(sessionId);
        this.status = "completed";
    }

    @Override
    public String getEventType() {
        return "chat_complete";
    }
}
