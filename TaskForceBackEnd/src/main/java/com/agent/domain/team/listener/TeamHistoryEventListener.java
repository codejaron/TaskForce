package com.agent.domain.team.listener;

import com.agent.domain.team.service.TeamHistoryPersistenceService;
import com.agent.infrastructure.event.events.LeadMessageEvent;
import com.agent.infrastructure.event.events.SessionCompleteEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Team 历史持久化事件监听器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamHistoryEventListener {

    private final TeamHistoryPersistenceService teamHistoryPersistenceService;

    @EventListener
    public void onLeadMessage(LeadMessageEvent event) {
        if (event == null) {
            return;
        }
        teamHistoryPersistenceService.persistLeadMessage(event.getSessionId(), event.getContent());
    }

    @EventListener
    public void onSessionComplete(SessionCompleteEvent event) {
        if (event == null) {
            return;
        }
        String summary = (event.getReason() == null || event.getReason().isBlank())
                ? "Team session completed"
                : event.getReason();
        teamHistoryPersistenceService.persistSystemMessage(event.getSessionId(), summary);
    }
}
