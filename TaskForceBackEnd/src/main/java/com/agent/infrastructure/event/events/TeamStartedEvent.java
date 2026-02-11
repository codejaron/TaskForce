package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 团队启动事件
 */
@Getter
public class TeamStartedEvent extends OrchestrationEvent {

    private String teamId;

    public TeamStartedEvent() {
        super();
    }

    public TeamStartedEvent(String sessionId, String teamId) {
        super(sessionId);
        this.teamId = teamId;
    }

    @Override
    public String getEventType() {
        return "team_started";
    }
}
