package com.agent.domain.team.listener;

import com.agent.infrastructure.event.events.ErrorEvent;
import com.agent.infrastructure.event.events.SessionCompleteEvent;
import com.agent.infrastructure.event.events.TeamStartedEvent;
import com.agent.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Team 模式会话状态落库监听器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamSessionStateListener {

    private final SessionService sessionService;

    @EventListener
    public void onTeamStarted(TeamStartedEvent event) {
        if (event == null || event.getSessionId() == null || event.getSessionId().isBlank()) {
            return;
        }
        updateStatus(event.getSessionId(), "RUNNING", "team_started");
    }

    @EventListener
    public void onSessionComplete(SessionCompleteEvent event) {
        if (event == null || event.getSessionId() == null || event.getSessionId().isBlank()) {
            return;
        }
        updateStatus(event.getSessionId(), "COMPLETED", "session_complete");
    }

    @EventListener
    public void onError(ErrorEvent event) {
        if (event == null || event.getSessionId() == null || event.getSessionId().isBlank()) {
            return;
        }
        updateStatus(event.getSessionId(), "FAILED", "error");
    }

    private void updateStatus(String sessionId, String status, String source) {
        try {
            sessionService.updateSessionStatus(sessionId, status);
            log.debug("[TeamSessionStateListener] Session status updated: sessionId={}, status={}, source={}",
                    sessionId, status, source);
        } catch (Exception e) {
            log.warn("[TeamSessionStateListener] Failed to update session status: sessionId={}, status={}, source={}, error={}",
                    sessionId, status, source, e.getMessage());
        }
    }
}
