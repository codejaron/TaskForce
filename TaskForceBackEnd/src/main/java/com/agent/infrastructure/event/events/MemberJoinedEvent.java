package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 成员加入事件
 */
@Getter
public class MemberJoinedEvent extends OrchestrationEvent {

    private String teamId;
    private String instanceId;
    private String name;
    private String role;

    public MemberJoinedEvent() {
        super();
    }

    public MemberJoinedEvent(String sessionId, String teamId, String instanceId, String name, String role) {
        super(sessionId);
        this.teamId = teamId;
        this.instanceId = instanceId;
        this.name = name;
        this.role = role;
    }

    @Override
    public String getEventType() {
        return "member_joined";
    }
}
