package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Lead 消息事件
 */
@Getter
public class LeadMessageEvent extends OrchestrationEvent {

    private String content;

    public LeadMessageEvent() {
        super();
    }

    public LeadMessageEvent(String sessionId, String content) {
        super(sessionId);
        this.content = content;
    }

    @Override
    public String getEventType() {
        return "lead_message";
    }
}
