package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Lead 输出事件
 * 用于实时推送 Lead 的流式思考/输出片段到前端。
 */
@Getter
public class LeadOutputEvent extends OrchestrationEvent {

    private String output;

    public LeadOutputEvent() {
        super();
    }

    public LeadOutputEvent(String sessionId, String output) {
        super(sessionId);
        this.output = output;
    }

    @Override
    public String getEventType() {
        return "lead_output";
    }
}

