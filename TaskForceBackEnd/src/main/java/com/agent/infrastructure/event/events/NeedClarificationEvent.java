package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 需要用户澄清事件
 */
@Getter
public class NeedClarificationEvent extends OrchestrationEvent {

    private final String question;

    public NeedClarificationEvent(String sessionId, String question) {
        super(sessionId);
        this.question = question;
    }

    @Override
    public String getEventType() {
        return "need_clarification";
    }
}
