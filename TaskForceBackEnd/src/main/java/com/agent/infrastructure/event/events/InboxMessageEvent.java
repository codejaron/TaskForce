package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

@Getter
public class InboxMessageEvent extends OrchestrationEvent {

    private String from;
    private String to;
    private String messageType;
    private String text;

    public InboxMessageEvent() {
        super();
    }

    public InboxMessageEvent(String sessionId, String from, String to, String messageType, String text) {
        super(sessionId);
        this.from = from;
        this.to = to;
        this.messageType = messageType;
        this.text = text;
    }

    @Override
    public String getEventType() {
        return "inbox_message";
    }
}
