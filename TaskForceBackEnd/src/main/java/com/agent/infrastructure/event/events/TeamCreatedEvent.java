package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 团队创建事件
 */
@Getter
public class TeamCreatedEvent extends OrchestrationEvent {

    private String teamId;
    private String leadInstanceId;

    public TeamCreatedEvent() {
        super();
    }

    public TeamCreatedEvent(String sessionId, String teamId, String leadInstanceId) {
        super(sessionId);
        this.teamId = teamId;
        this.leadInstanceId = leadInstanceId;
    }

    @Override
    public String getEventType() {
        return "team_created";
    }
}
