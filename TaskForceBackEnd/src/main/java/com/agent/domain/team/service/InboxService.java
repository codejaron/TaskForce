package com.agent.domain.team.service;

import com.agent.domain.team.model.Team;
import com.agent.domain.team.model.TeamMember;
import com.agent.domain.team.model.TeamMessage;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.InboxMessageEvent;
import com.agent.infrastructure.persistence.redis.RedisInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 收件箱领域服务
 * 负责消息收发管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxService {

    private final RedisInboxRepository inboxRepository;
    private final TeamService teamService;
    private final EventBus eventBus;

    /**
     * 发送消息
     * @param message 消息对象
     */
    public void send(TeamMessage message) {
        if (message == null || message.getTo() == null) {
            throw new IllegalArgumentException("Message or recipient cannot be null");
        }

        // 从 instanceId 中提取 sessionId
        // 假设格式：{sessionId}_{suffix} 或 {sessionId}_lead
        String instanceId = message.getTo();
        String sessionId = extractSessionId(instanceId);

        log.info("[InboxService] Sending message: from={}, to={}, sessionId={}",
                message.getFrom(), instanceId, sessionId);

        inboxRepository.send(sessionId, instanceId, message);
        eventBus.publish(sessionId, new InboxMessageEvent(
                sessionId,
                message.getFrom(),
                message.getFromInstanceId(),
                instanceId,
                message.getType(),
                message.getText()
        ));
    }

    /**
     * 广播消息
     * @param sessionId 会话 ID
     * @param message 消息对象
     */
    public void broadcast(String sessionId, TeamMessage message) {
        if (sessionId == null || message == null) {
            throw new IllegalArgumentException("SessionId and message cannot be null");
        }

        log.info("[InboxService] Broadcasting message: sessionId={}, from={}",
                sessionId, message.getFrom());

        try {
            // 获取团队所有成员
            Team team = teamService.getTeamBySessionId(sessionId);
            if (team == null || team.getMembers() == null) {
                log.warn("[InboxService] No team found for broadcast: sessionId={}", sessionId);
                return;
            }

            // 向所有成员发送消息（除了发送者自己）
            for (TeamMember member : team.getMembers()) {
                if (!member.getName().equals(message.getFrom())) {
                    inboxRepository.send(sessionId, member.getName(), message);
                }
            }

            log.info("[InboxService] Broadcasted to {} members", team.getMembers().size());
        } catch (Exception e) {
            log.error("[InboxService] Failed to broadcast message: sessionId={}", sessionId, e);
            throw new RuntimeException("Failed to broadcast message", e);
        }
    }

    /**
     * 读取收件箱
     * @param instanceId 实例 ID
     * @return 消息列表
     */
    public List<TeamMessage> readInbox(String instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("InstanceId cannot be null");
        }

        String sessionId = extractSessionId(instanceId);
        log.debug("[InboxService] Reading inbox: sessionId={}, instanceId={}", sessionId, instanceId);

        return inboxRepository.readInbox(sessionId, instanceId);
    }

    /**
     * 阻塞读取收件箱（最多阻塞 blockTimeout）
     */
    public List<TeamMessage> readInboxBlocking(String instanceId, Duration blockTimeout, int limit) {
        if (instanceId == null) {
            throw new IllegalArgumentException("InstanceId cannot be null");
        }

        String sessionId = extractSessionId(instanceId);
        log.debug("[InboxService] Blocking read inbox: sessionId={}, instanceId={}, timeout={}, limit={}",
                sessionId, instanceId, blockTimeout, limit);

        return inboxRepository.readInboxBlocking(sessionId, instanceId, blockTimeout, limit);
    }

    /**
     * 检查是否有新消息
     * @param instanceId 实例 ID
     * @return 是否有新消息
     */
    public boolean hasNewMessages(String instanceId) {
        if (instanceId == null) {
            return false;
        }

        String sessionId = extractSessionId(instanceId);
        return inboxRepository.hasNewMessages(sessionId, instanceId);
    }

    /**
     * 清空收件箱
     * @param instanceId 实例 ID
     */
    public void clearInbox(String instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("InstanceId cannot be null");
        }

        String sessionId = extractSessionId(instanceId);
        log.info("[InboxService] Clearing inbox: sessionId={}, instanceId={}", sessionId, instanceId);

        inboxRepository.clearInbox(sessionId, instanceId);
    }

    /**
     * 从 instanceId 中提取 sessionId
     * 假设格式：{sessionId}_{suffix}
     * 例如：session123_lead -> session123
     */
    private String extractSessionId(String instanceId) {
        if (instanceId == null || !instanceId.contains("_")) {
            // 如果没有下划线，假设整个就是 sessionId
            return instanceId;
        }

        // 找到最后一个下划线的位置
        int lastUnderscoreIndex = instanceId.lastIndexOf("_");
        return instanceId.substring(0, lastUnderscoreIndex);
    }
}
