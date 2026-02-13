package com.agent.domain.team.lead.hook;

import com.agent.domain.team.model.TeamMessage;
import com.agent.infrastructure.persistence.redis.RedisInboxRepository;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Lead 收件箱检查 Hook。
 */
@Slf4j
public class LeadInboxCheckHook extends MessagesModelHook {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RedisInboxRepository inboxRepository;
    private final String sessionId;
    private final String leadInstanceId;

    public LeadInboxCheckHook(RedisInboxRepository inboxRepository, String sessionId) {
        this.inboxRepository = inboxRepository;
        this.sessionId = sessionId;
        this.leadInstanceId = sessionId + "_lead";
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        try {
            if (!inboxRepository.hasNewMessages(sessionId, leadInstanceId)) {
                return new AgentCommand(List.of(), UpdatePolicy.APPEND);
            }

            List<TeamMessage> inboxMessages = inboxRepository.readInbox(sessionId, leadInstanceId);
            if (inboxMessages.isEmpty()) {
                return new AgentCommand(List.of(), UpdatePolicy.APPEND);
            }

            String reminder = formatMessagesAsReminder(inboxMessages);
            log.info("[LeadInboxCheckHook] Injected {} inbox messages for lead: sessionId={}",
                    inboxMessages.size(), sessionId);
            return new AgentCommand(List.of(new SystemMessage(reminder)), UpdatePolicy.APPEND);
        } catch (Exception e) {
            log.error("[LeadInboxCheckHook] Failed to read lead inbox: sessionId={}", sessionId, e);
            return new AgentCommand(List.of(), UpdatePolicy.APPEND);
        }
    }

    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        return new AgentCommand(List.of(), UpdatePolicy.APPEND);
    }

    @Override
    public int getOrder() {
        return 140;
    }

    @Override
    public String getName() {
        return "LeadInboxCheckHook";
    }

    private String formatMessagesAsReminder(List<TeamMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append("New inbox messages are available:\n");
        for (TeamMessage message : messages) {
            String from = message.getFrom() == null || message.getFrom().isBlank()
                    ? "unknown"
                    : message.getFrom();
            String timestamp = message.getTimestamp() == null
                    ? "unknown-time"
                    : message.getTimestamp().format(FORMATTER);
            String text = message.getText() == null ? "" : message.getText();
            sb.append("- From ").append(from).append(" (").append(timestamp).append("): \"")
                    .append(text).append("\"\n");
        }
        sb.append("</system-reminder>");
        return sb.toString();
    }
}
