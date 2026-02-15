package com.agent.domain.team.listener;

import com.agent.domain.team.service.TeamHistoryPersistenceService;
import com.agent.infrastructure.event.events.InboxMessageEvent;
import com.agent.infrastructure.event.events.LeadMessageEvent;
import com.agent.infrastructure.event.events.SessionCompleteEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Team 历史持久化事件监听器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamHistoryEventListener {

    private static final Pattern WORKER_LABEL_PATTERN = Pattern.compile("^worker-(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final TeamHistoryPersistenceService teamHistoryPersistenceService;

    @EventListener
    public void onLeadMessage(LeadMessageEvent event) {
        if (event == null) {
            return;
        }
        teamHistoryPersistenceService.persistLeadMessage(event.getSessionId(), event.getContent());
    }

    @EventListener
    public void onInboxMessage(InboxMessageEvent event) {
        if (event == null || event.getSessionId() == null) {
            return;
        }

        String text = event.getText();
        if (text == null || text.isBlank()) {
            return;
        }

        String sender = safeLower(event.getFrom());
        if ("user".equals(sender)) {
            // 用户消息已经在 Controller 写库，这里避免重复。
            return;
        }

        if (isLeadSender(sender)) {
            teamHistoryPersistenceService.persistLeadMessage(event.getSessionId(), text);
            return;
        }

        if (isWorkerSender(sender)) {
            String workerInstanceId = resolveWorkerInstanceId(
                    event.getSessionId(),
                    event.getFrom(),
                    event.getFromInstanceId()
            );
            teamHistoryPersistenceService.persistWorkerMessage(event.getSessionId(), workerInstanceId, text);
        }
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

    private boolean isLeadSender(String sender) {
        return "lead".equals(sender) || "team-lead".equals(sender);
    }

    private boolean isWorkerSender(String sender) {
        return sender != null && sender.startsWith("worker-");
    }

    private String resolveWorkerInstanceId(String sessionId, String senderLabel, String fromInstanceId) {
        if (fromInstanceId != null && !fromInstanceId.isBlank()) {
            return fromInstanceId;
        }
        if (sessionId == null || senderLabel == null) {
            return null;
        }

        Matcher matcher = WORKER_LABEL_PATTERN.matcher(senderLabel.trim());
        if (!matcher.matches()) {
            return null;
        }
        return sessionId + "_w" + matcher.group(1);
    }

    private String safeLower(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
