package com.agent.domain.worker.hook;

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
 * Inbox 检查 Hook
 * 在每次 model call 前检查 Worker 的 inbox，如果有新消息就注入到上下文中
 */
@Slf4j
public class InboxCheckHook extends MessagesModelHook {

    private final RedisInboxRepository inboxRepository;
    private final String sessionId;
    private final String instanceId;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public InboxCheckHook(RedisInboxRepository inboxRepository,
                          String sessionId,
                          String instanceId) {
        this.inboxRepository = inboxRepository;
        this.sessionId = sessionId;
        this.instanceId = instanceId;
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        try {
            // 检查是否有新消息
            if (!inboxRepository.hasNewMessages(sessionId, instanceId)) {
                return new AgentCommand(List.of(), UpdatePolicy.APPEND);
            }

            // 读取所有消息
            List<TeamMessage> teamMessages = inboxRepository.readInbox(sessionId, instanceId);

            if (teamMessages.isEmpty()) {
                return new AgentCommand(List.of(), UpdatePolicy.APPEND);
            }

            // 格式化为 system reminder
            String reminder = formatMessagesAsReminder(teamMessages);
            SystemMessage systemMessage = new SystemMessage(reminder);

            log.info("[InboxCheckHook] Injected {} messages for worker: {}",
                    teamMessages.size(), instanceId);

            // 追加 SystemMessage 到消息列表
            return new AgentCommand(List.of(systemMessage), UpdatePolicy.APPEND);
        } catch (Exception e) {
            log.error("[InboxCheckHook] Failed to check inbox for worker: {}", instanceId, e);
            // 出错时返回空命令，不影响正常流程
            return new AgentCommand(List.of(), UpdatePolicy.APPEND);
        }
    }

    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        // 不需要在 model call 后处理
        return new AgentCommand(List.of(), UpdatePolicy.APPEND);
    }

    @Override
    public int getOrder() {
        return 50; // 中等优先级，在 ModelCallLimitHook (100) 之后执行
    }

    @Override
    public String getName() {
        return "InboxCheckHook";
    }

    /**
     * 格式化消息为 system reminder
     */
    private String formatMessagesAsReminder(List<TeamMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append("You have new messages in your inbox:\n");

        for (TeamMessage msg : messages) {
            String timestamp = msg.getTimestamp().format(FORMATTER);
            sb.append(String.format("- From %s (%s): \"%s\"\n",
                    msg.getFrom(), timestamp, msg.getText()));
        }

        sb.append("</system-reminder>");
        return sb.toString();
    }
}
