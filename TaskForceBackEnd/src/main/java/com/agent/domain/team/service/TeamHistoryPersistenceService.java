package com.agent.domain.team.service;

import com.agent.infrastructure.persistence.entity.Message;
import com.agent.service.MessageService;
import com.agent.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Team 历史持久化服务。
 * 仅持久化对用户可见的聊天时间线（不持久化任务/收件箱运行态）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamHistoryPersistenceService {

    private static final String TYPE_TEAM_USER = "TEAM_USER";
    private static final String TYPE_TEAM_LEAD = "TEAM_LEAD";
    private static final String TYPE_TEAM_WORKER = "TEAM_WORKER";
    private static final String TYPE_TEAM_SYSTEM = "TEAM_SYSTEM";

    private final MessageService messageService;
    private final SessionService sessionService;

    public void persistUserMessage(String sessionId, String content) {
        persist(sessionId, "user", TYPE_TEAM_USER, "user", content);
    }

    public void persistUserMessageToWorker(String sessionId, String instanceId, String content) {
        persist(sessionId, "user", TYPE_TEAM_USER, "worker:" + instanceId, content);
    }

    public void persistLeadMessage(String sessionId, String content) {
        persist(sessionId, "assistant", TYPE_TEAM_LEAD, "Lead", content);
    }

    public void persistWorkerMessage(String sessionId, String workerInstanceId, String content) {
        String workerAgentName = (workerInstanceId == null || workerInstanceId.isBlank())
                ? "worker"
                : "worker:" + workerInstanceId;
        persist(sessionId, "assistant", TYPE_TEAM_WORKER, workerAgentName, content);
    }

    public void persistSystemMessage(String sessionId, String content) {
        persist(sessionId, "system", TYPE_TEAM_SYSTEM, "System", content);
    }

    private void persist(String sessionId, String role, String messageType, String agentName, String content) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }

        if (!isTeamSession(sessionId)) {
            return;
        }

        try {
            Message message = Message.builder()
                    .sessionId(sessionId)
                    .role(role)
                    .messageType(messageType)
                    .agentName(agentName)
                    .content(content)
                    .status("COMPLETED")
                    .build();
            messageService.saveMessage(message);
        } catch (Exception e) {
            // 历史持久化失败不能阻断主流程
            log.warn("[TeamHistoryPersistenceService] Persist message failed: sessionId={}, type={}",
                    sessionId, messageType, e);
        }
    }

    private boolean isTeamSession(String sessionId) {
        try {
            return "TEAM".equalsIgnoreCase(sessionService.getSessionById(sessionId).getType());
        } catch (Exception e) {
            log.debug("[TeamHistoryPersistenceService] Skip persist, session unavailable: sessionId={}", sessionId);
            return false;
        }
    }
}
