package com.agent.service;

import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.lead.TeamLeadAgent;
import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.repository.TeamRepository;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.team.service.TeamService;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.infrastructure.llm.ChatModelFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.OrchestrationEvent;
import com.agent.infrastructure.event.events.ErrorEvent;
import com.agent.infrastructure.event.events.TeamCreatedEvent;
import com.agent.infrastructure.event.events.TeamStartedEvent;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.infrastructure.prompt.PromptManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Team Orchestration Service
 * 团队编排服务 - Team Lead 和 Worker 协调的入口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamOrchestrationService {

    private final TeamLeadAgent teamLeadAgent;
    private final WorkerInstanceManager workerInstanceManager;
    private final TaskBoardService taskBoardService;
    private final TeamService teamService;
    private final TeamRepository teamRepository;
    private final InboxService inboxService;
    private final ChatModelFactory chatModelFactory;
    private final AgentMapper agentMapper;
    private final PromptManager promptManager;
    private final EventBus eventBus;
    private final SessionExecutionTracker executionTracker;
    private final ObjectMapper objectMapper;
    private final AgentExecutionStateService executionStateService;

    private final Map<String, LeadRuntimeContext> leadRuntimeContexts = new ConcurrentHashMap<>();

    /**
     * 启动团队会话
     * 创建团队 + 启动 Lead Agent
     *
     * @param sessionId 会话 ID
     * @param userGoal  用户目标
     */
    public void startTeamSession(String sessionId, String userGoal) {
        log.info("[TeamOrchestrationService] Starting team session: sessionId={}, goal={}",
                sessionId, userGoal);

        try {
            // 1. 获取 Lead Agent 配置
            Agent leadAgent = getLeadAgent();
            if (leadAgent == null) {
                eventBus.publish(sessionId, new ErrorEvent(sessionId, "Lead Agent not found with roleType=PLANNER"));
                return;
            }

            // 2. 创建团队（如果不存在）
            String leadInstanceId = sessionId + "_lead";
            String teamId = null;
            try {
                if (!teamRepository.existsBySessionId(sessionId)) {
                    var team = teamService.createTeam(sessionId, leadInstanceId);
                    teamId = team.getTeamId();
                    // 发送 team_created 事件
                    eventBus.publish(sessionId, new TeamCreatedEvent(sessionId, teamId, leadInstanceId));
                    log.info("[TeamOrchestrationService] Team created: sessionId={}, teamId={}", sessionId, teamId);
                } else {
                    teamId = teamService.getTeamBySessionId(sessionId).getTeamId();
                }
            } catch (Exception e) {
                log.error("[TeamOrchestrationService] Team creation failed", e);
                eventBus.publish(sessionId, new ErrorEvent(sessionId, "Team creation failed: " + e.getMessage()));
                return;
            }

            // 3. 发送 team_started 事件
            eventBus.publish(sessionId, new TeamStartedEvent(sessionId, teamId));

            // 4. 创建 ChatModel
            ChatModel chatModel = chatModelFactory.createChatModel(
                    leadAgent.getProviderId(),
                    leadAgent.getModel()
            );

            // 5. 配置模型参数
            OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                    .temperature(leadAgent.getTemperature().doubleValue())
                    .maxTokens(leadAgent.getMaxTokens())
                    .build();

            // 6. 构建 Lead Prompt
            String leadPrompt = promptManager.buildTeamLeadPrompt(userGoal);
            LeadRuntimeContext runtimeContext = new LeadRuntimeContext(
                    leadAgent.getId(),
                    leadPrompt,
                    chatModel,
                    chatOptions
            );
            leadRuntimeContexts.put(sessionId, runtimeContext);

            // 7. 启动 Team Lead Agent
            teamLeadAgent.startLeadLoop(
                    sessionId,
                    leadAgent.getId(),
                    leadPrompt,
                    chatModel,
                    chatOptions,
                    ""
            );

            log.info("[TeamOrchestrationService] Team Lead started: sessionId={}", sessionId);

        } catch (Exception e) {
            log.error("[TeamOrchestrationService] Failed to start team session: sessionId={}", sessionId, e);
            leadRuntimeContexts.remove(sessionId);
            eventBus.publish(sessionId, new ErrorEvent(sessionId, e.getMessage()));
        }
    }

    /**
     * 停止会话
     * 优雅关闭整个团队
     *
     * @param sessionId 会话 ID
     */
    public void stopSession(String sessionId) {
        log.info("[TeamOrchestrationService] Stopping session: sessionId={}", sessionId);

        try {
            // 1. 停止 Team Lead
            teamLeadAgent.stopLeadLoop(sessionId);
            leadRuntimeContexts.remove(sessionId);
            executionStateService.setStatus(sessionId + "_lead", AgentExecutionStatus.COMPLETED, "session stopped");

            // 2. 停止所有 Worker
            workerInstanceManager.shutdownAllBySession(sessionId);

            // 3. 清理 Lead checkpoint
            teamLeadAgent.clearLeadCheckpoint(sessionId);

            // 4. 关闭团队
            try {
                teamService.shutdown(sessionId);
            } catch (Exception e) {
                log.warn("[TeamOrchestrationService] Team shutdown skipped or failed: {}", e.getMessage());
            }

            // 5. 清理任务板（可选：保留任务历史）
            // taskBoardService.deleteAllTasks(sessionId);

            // 6. 取消订阅
            eventBus.unsubscribe(sessionId);

            // 7. 取消执行跟踪
            executionTracker.cancelExecution(sessionId);

            log.info("[TeamOrchestrationService] Session stopped: sessionId={}", sessionId);

        } catch (Exception e) {
            log.error("[TeamOrchestrationService] Error stopping session: sessionId={}", sessionId, e);
        }
    }

    /**
     * 向 Team Lead 发送消息
     *
     * @param sessionId 会话 ID
     * @param message   消息内容
     */
    public void sendMessageToLead(String sessionId, String message) {
        log.info("[TeamOrchestrationService] Sending message to Lead: sessionId={}, message={}", sessionId, message);
        teamLeadAgent.sendMessageToLead(sessionId, message);
    }

    public boolean resumeLeadIfWaiting(String sessionId) {
        String leadInstanceId = sessionId + "_lead";
        LeadRuntimeContext runtimeContext = leadRuntimeContexts.get(sessionId);
        if (runtimeContext == null) {
            return false;
        }

        boolean transitioned = executionStateService.transitionIf(
                leadInstanceId,
                AgentExecutionStatus.WAITING_REPLY,
                AgentExecutionStatus.RUNNING,
                "wakeup by inbox message"
        );
        if (!transitioned) {
            return false;
        }

        try {
            List<TeamMessage> messages = inboxService.readInbox(leadInstanceId);
            String inputMessage = formatWakeupInput(messages);
            teamLeadAgent.startLeadLoop(
                    sessionId,
                    runtimeContext.agentId(),
                    runtimeContext.systemPrompt(),
                    runtimeContext.chatModel(),
                    runtimeContext.chatOptions(),
                    inputMessage
            );
            log.info("[TeamOrchestrationService] Lead resumed: sessionId={}", sessionId);
            return true;
        } catch (Exception e) {
            executionStateService.setStatus(
                    leadInstanceId,
                    AgentExecutionStatus.FAILED,
                    "lead resume failed: " + e.getMessage()
            );
            log.error("[TeamOrchestrationService] Failed to resume lead: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 向特定 Worker 发送消息
     *
     * @param sessionId  会话 ID
     * @param instanceId Worker 实例 ID
     * @param message    消息内容
     */
    public void sendMessageToWorker(String sessionId, String instanceId, String message) {
        log.info("[TeamOrchestrationService] Sending message to Worker: sessionId={}, instanceId={}, message={}",
                sessionId, instanceId, message);

        try {
            TeamMessage teamMessage = TeamMessage.builder()
                    .from("user")
                    .to(instanceId)
                    .text(message)
                    .type("USER_MESSAGE")
                    .build();

            inboxService.send(teamMessage);

            log.info("[TeamOrchestrationService] Message sent to Worker successfully: instanceId={}", instanceId);
        } catch (Exception e) {
            log.error("[TeamOrchestrationService] Failed to send message to Worker: instanceId={}", instanceId, e);
            throw new RuntimeException("Failed to send message to Worker", e);
        }
    }

    /**
     * 获取会话状态
     * 查询团队/任务/Worker 状态
     *
     * @param sessionId 会话 ID
     * @return 会话状态 JSON
     */
    public String getSessionStatus(String sessionId) {
        try {
            var team = teamService.getTeamBySessionId(sessionId);
            var tasks = taskBoardService.listTasks(sessionId);
            var workers = workerInstanceManager.getRunningWorkers(sessionId);

            var status = new SessionStatus(
                    sessionId,
                    team != null ? team.getStatus().toString() : "NOT_FOUND",
                    tasks.size(),
                    workers.size()
            );

            return objectMapper.writeValueAsString(status);

        } catch (Exception e) {
            log.error("[TeamOrchestrationService] Failed to get session status: sessionId={}", sessionId, e);
            return "{\"error\":\"Failed to get session status\"}";
        }
    }

    /**
     * 序列化事件为 JSON
     */
    private String serializeEvent(OrchestrationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("[TeamOrchestrationService] Failed to serialize event: {}", event.getEventType(), e);
            return "{\"error\":\"Serialization failed\"}";
        }
    }

    private String formatWakeupInput(List<TeamMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (TeamMessage message : messages) {
            if (message.getText() == null || message.getText().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("From ").append(message.getFrom() == null ? "unknown" : message.getFrom())
                    .append(": ").append(message.getText());
        }
        return builder.toString();
    }

    /**
     * 会话状态 DTO
     */
    private record SessionStatus(
            String sessionId,
            String status,
            int taskCount,
            int workerCount
    ) {}

    private record LeadRuntimeContext(
            Long agentId,
            String systemPrompt,
            ChatModel chatModel,
            OpenAiChatOptions chatOptions
    ) {}

    /**
     * 获取 Lead Agent 配置
     */
    private Agent getLeadAgent() {
        try {
            return agentMapper.selectOne(
                    new LambdaQueryWrapper<Agent>()
                            .eq(Agent::getRoleType, "PLANNER")
                            .last("LIMIT 1")
            );
        } catch (Exception e) {
            log.error("[TeamOrchestrationService] Failed to get lead agent", e);
            return null;
        }
    }
}
