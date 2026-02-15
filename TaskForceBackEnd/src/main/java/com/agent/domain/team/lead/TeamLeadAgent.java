package com.agent.domain.team.lead;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.execution.service.ExecutionWaitIntentService;
import com.agent.domain.team.lead.scheduling.LeadSchedulingDecision;
import com.agent.domain.team.lead.scheduling.LeadSchedulingDecisionService;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.infrastructure.agent.CheckpointThreadIds;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.LeadOutputEvent;
import com.agent.infrastructure.event.events.SessionCompleteEvent;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.service.AgentService;
import com.agent.service.SessionExecutionTracker;
import com.agent.service.SessionService;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.List;

/**
 * Team Lead Agent
 * Lead 的 ReAct 循环入口，负责：
 * 1. 处理用户消息
 * 2. 接收 Worker 汇报
 * 3. 协调任务分配
 * 4. 管理团队状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamLeadAgent {

    private final ReactAgentFactory reactAgentFactory;
    private final SessionExecutionTracker executionTracker;
    private final InboxService inboxService;
    private final SessionService sessionService;
    private final AgentService agentService;
    private final BaseCheckpointSaver checkpointSaver;
    private final AgentExecutionStateService executionStateService;
    private final ExecutionWaitIntentService waitIntentService;
    private final LeadSchedulingDecisionService leadSchedulingDecisionService;
    private final WorkerInstanceManager workerInstanceManager;
    private final EventBus eventBus;

    private static final int MAX_REACT_ITERATIONS = 50; // Lead 需要更多迭代次数


    /**
     * 启动 Lead 的持久 ReAct 循环
     *
     * @param sessionId     会话 ID
     * @param agentId       Agent ID
     * @param userMessage   用户消息
     * @param chatModel     聊天模型
     * @param chatOptions   模型参数
     * @param inputMessage  本轮输入消息（唤醒续跑时传入）
     * @return Disposable 用于取消执行
     */
    public Disposable startLeadLoop(
            String sessionId,
            Long agentId,
            String userMessage,
            ChatModel chatModel,
            OpenAiChatOptions chatOptions,
            String inputMessage) {

        log.info("[TeamLeadAgent] Starting Lead loop: sessionId={}, agentId={}", sessionId, agentId);
        String leadInstanceId = sessionId + "_lead";

        waitIntentService.clear(leadInstanceId);
        executionStateService.setStatus(leadInstanceId, AgentExecutionStatus.EXECUTING, "lead loop started");

        try {
            // 1. 加载可用的 Agent
            List<Agent> availableAgents = loadAvailableAgents(sessionId);
            if (availableAgents.isEmpty()) {
                throw new IllegalStateException(
                        "No worker agents are bound to this session. Please add agents to the session before starting Team mode."
                );
            }
            String agentRoster = formatAgentRoster(availableAgents);
            String systemPrompt = userMessage + "\n\n## 可用 Agent\n" + agentRoster
                    + "\n重要： spawn_worker 的 agentId 必须使用上面的数字 ID，不要编造。"
                    + "\n重要：后续 send_message/shutdown_worker 统一使用 workerId（数字），而不是 agentId。"
                    + "\n如果不确定当前有哪些 workerId，先调用 list_teammates。\n";

            // 2. 通过工厂收敛 Lead Agent 组装逻辑
            ReactAgent reactAgent = reactAgentFactory.buildTeamLeadAgent(
                    agentId,
                    sessionId,
                    systemPrompt,
                    MAX_REACT_ITERATIONS,
                    chatModel,
                    chatOptions,
                    inputMessage == null || inputMessage.isBlank()
            );

            // 3. 配置 RunnableConfig（传递 sessionId 到 metadata）
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(CheckpointThreadIds.leadThreadId(sessionId))
                    .addMetadata("sessionId", sessionId)
                    .build();

            // 4. 启动流式执行
            String runInput = inputMessage == null ? "" : inputMessage;
            Disposable disposable = reactAgent.stream(runInput, config)
                    .doOnNext(nodeOutput -> {
                        if (nodeOutput instanceof StreamingOutput streamingOutput) {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                log.debug("[TeamLeadAgent] Received chunk: {}", chunk);
                                String sanitized = sanitizeLeadChunk(chunk);
                                if (!sanitized.isEmpty()) {
                                    try {
                                        eventBus.publish(sessionId, new LeadOutputEvent(sessionId, sanitized));
                                    } catch (Exception e) {
                                        log.warn("[TeamLeadAgent] Failed to publish lead_output chunk: sessionId={}", sessionId, e);
                                    }
                                }
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        boolean waitIntent = waitIntentService.consumeWaitingReply(leadInstanceId);
                        List<TeamMessage> boundaryMessages = safeReadLeadInbox(leadInstanceId);
                        if (waitIntent && boundaryMessages.isEmpty()) {
                            executionStateService.setStatus(
                                    leadInstanceId,
                                    AgentExecutionStatus.WAITING_REPLY,
                                    "waiting worker reply"
                            );
                            List<TeamMessage> waitingLateMessages = safeReadLeadInbox(leadInstanceId);
                            if (!waitingLateMessages.isEmpty()) {
                                continueLeadLoop(
                                        sessionId,
                                        leadInstanceId,
                                        agentId,
                                        userMessage,
                                        chatModel,
                                        chatOptions,
                                        formatWakeupInput(waitingLateMessages),
                                        "lead continues due to inbox messages after entering WAITING_REPLY"
                                );
                                log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                return;
                            }
                        } else if (!boundaryMessages.isEmpty()) {
                            continueLeadLoop(
                                    sessionId,
                                    leadInstanceId,
                                    agentId,
                                    userMessage,
                                    chatModel,
                                    chatOptions,
                                    formatWakeupInput(boundaryMessages),
                                    "lead continues due to inbox messages at round boundary"
                            );
                            log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                            return;
                        } else {
                            LeadSchedulingDecision decision = leadSchedulingDecisionService.evaluate(sessionId);
                            if (decision.shouldContinueNow()) {
                                continueLeadLoop(
                                        sessionId,
                                        leadInstanceId,
                                        agentId,
                                        userMessage,
                                        chatModel,
                                        chatOptions,
                                        "",
                                        "lead continues due to pending inbox/taskboard work"
                                );
                                log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                return;
                            }
                            if (decision.shouldWait()) {
                                executionStateService.setStatus(
                                        leadInstanceId,
                                        AgentExecutionStatus.IDLE,
                                        "idle waiting for inbox/taskboard wakeup"
                                );
                                List<TeamMessage> idleLateMessages = safeReadLeadInbox(leadInstanceId);
                                if (!idleLateMessages.isEmpty()) {
                                    continueLeadLoop(
                                            sessionId,
                                            leadInstanceId,
                                            agentId,
                                            userMessage,
                                            chatModel,
                                            chatOptions,
                                            formatWakeupInput(idleLateMessages),
                                            "lead continues due to inbox messages after entering IDLE"
                                    );
                                    log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                    return;
                                }
                                log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                return;
                            }

                            // Final shutdown guard: drain inbox once more right before completion.
                            List<TeamMessage> finalMessages = safeReadLeadInbox(leadInstanceId);
                            if (!finalMessages.isEmpty()) {
                                continueLeadLoop(
                                        sessionId,
                                        leadInstanceId,
                                        agentId,
                                        userMessage,
                                        chatModel,
                                        chatOptions,
                                        formatWakeupInput(finalMessages),
                                        "lead continues due to late inbox messages before completion"
                                );
                                log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                return;
                            }

                            LeadSchedulingDecision finalDecision = leadSchedulingDecisionService.evaluate(sessionId);
                            if (finalDecision.shouldContinueNow()) {
                                continueLeadLoop(
                                        sessionId,
                                        leadInstanceId,
                                        agentId,
                                        userMessage,
                                        chatModel,
                                        chatOptions,
                                        "",
                                        "lead continues due to late inbox/taskboard work"
                                );
                                log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                return;
                            }
                            if (finalDecision.shouldWait()) {
                                executionStateService.setStatus(
                                        leadInstanceId,
                                        AgentExecutionStatus.IDLE,
                                        "idle waiting after final recheck"
                                );
                                log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                return;
                            }

                            List<TeamMessage> shutdownBoundaryMessages = safeReadLeadInbox(leadInstanceId);
                            if (!shutdownBoundaryMessages.isEmpty()) {
                                continueLeadLoop(
                                        sessionId,
                                        leadInstanceId,
                                        agentId,
                                        userMessage,
                                        chatModel,
                                        chatOptions,
                                        formatWakeupInput(shutdownBoundaryMessages),
                                        "lead continues due to inbox messages before shutdown"
                                );
                                log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                                return;
                            }

                            workerInstanceManager.shutdownAllBySession(sessionId);
                            executionStateService.setStatus(
                                    leadInstanceId,
                                    AgentExecutionStatus.COMPLETED,
                                    "lead run completed"
                            );
                            eventBus.publish(sessionId, new SessionCompleteEvent(
                                    sessionId,
                                    "Team lead completed all tasks"
                            ));
                            clearLeadCheckpoint(sessionId);
                        }
                        log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                    })
                    .doOnError(e -> {
                        executionStateService.setStatus(
                                leadInstanceId,
                                AgentExecutionStatus.FAILED,
                                "lead loop error: " + e.getMessage()
                        );
                        log.error("[TeamLeadAgent] Lead loop error: sessionId={}", sessionId, e);
                    })
                    .subscribe();

            // 5. 注册 Disposable
            executionTracker.registerDisposable(sessionId, disposable);

            log.info("[TeamLeadAgent] Lead loop started successfully: sessionId={}", sessionId);
            return disposable;

        } catch (Exception e) {
            log.error("[TeamLeadAgent] Failed to start Lead loop: sessionId={}", sessionId, e);
            throw new RuntimeException("Failed to start Team Lead Agent", e);
        }
    }

    /**
     * 向 Lead 发送消息（用于 Worker 汇报或用户追加消息）
     *
     * @param sessionId 会话 ID
     * @param message   消息内容
     */
    public void sendMessageToLead(String sessionId, String message) {
        log.info("[TeamLeadAgent] Sending message to Lead: sessionId={}, message={}", sessionId, message);

        try {
            // Lead 的实例 ID 格式：sessionId + "_lead"
            String leadInstanceId = sessionId + "_lead";

            // 构建消息
            TeamMessage teamMessage = TeamMessage.builder()
                    .from("user")
                    .to(leadInstanceId)
                    .text(message)
                    .type("USER_MESSAGE")
                    .build();

            // 发送到 Lead 的收件箱
            inboxService.send(teamMessage);

            log.info("[TeamLeadAgent] Message sent to Lead inbox: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("[TeamLeadAgent] Failed to send message to Lead: sessionId={}", sessionId, e);
            throw new RuntimeException("Failed to send message to Lead", e);
        }
    }

    /**
     * 停止 Lead 循环
     *
     * @param sessionId 会话 ID
     */
    public void stopLeadLoop(String sessionId) {
        log.info("[TeamLeadAgent] Stopping Lead loop: sessionId={}", sessionId);
        executionTracker.cancelExecution(sessionId);
    }

    public void clearLeadCheckpoint(String sessionId) {
        String threadId = CheckpointThreadIds.leadThreadId(sessionId);
        try {
            checkpointSaver.release(RunnableConfig.builder().threadId(threadId).build());
            log.info("[TeamLeadAgent] Cleared lead checkpoint: threadId={}", threadId);
        } catch (IllegalStateException e) {
            log.debug("[TeamLeadAgent] No lead checkpoint found to clear: threadId={}", threadId);
        } catch (Exception e) {
            log.warn("[TeamLeadAgent] Failed to clear lead checkpoint: threadId={}", threadId, e);
        }
    }

    /**
     * 加载会话中可用的 Agent
     *
     * @param sessionId 会话 ID
     * @return Agent 列表
     */
    private List<Agent> loadAvailableAgents(String sessionId) {
        try {
            var sessionAgents = sessionService.getSessionAgents(sessionId);
            if (sessionAgents.isEmpty()) {
                log.warn("[TeamLeadAgent] No agents bound to session: sessionId={}", sessionId);
                return List.of();
            }

            return sessionAgents.stream()
                    .map(sa -> {
                        try {
                            return agentService.getAgentById(sa.getAgentId());
                        } catch (Exception e) {
                            log.warn("[TeamLeadAgent] Failed to load agent: agentId={}", sa.getAgentId());
                            return null;
                        }
                    })
                    .filter(agent -> agent != null)
                    .toList();
        } catch (Exception e) {
            log.error("[TeamLeadAgent] Failed to load available agents: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 格式化 Agent 列表为文本
     *
     * @param agents Agent 列表
     * @return 格式化的文本
     */
    private String formatAgentRoster(List<Agent> agents) {
        if (agents == null || agents.isEmpty()) {
            return "(No agents available)";
        }

        StringBuilder sb = new StringBuilder();
        for (Agent agent : agents) {
            String description = agent.getDescription();
            if (description == null || description.isBlank()) {
                description = "General task execution";
            }

            sb.append(String.format("- ID: %s, Name: %s, Description: %s\n",
                    agent.getId(),
                    agent.getName(),
                    description));
        }
        return sb.toString();
    }

    private void continueLeadLoop(
            String sessionId,
            String leadInstanceId,
            Long agentId,
            String userMessage,
            ChatModel chatModel,
            OpenAiChatOptions chatOptions,
            String inputMessage,
            String detail) {
        executionStateService.setStatus(leadInstanceId, AgentExecutionStatus.EXECUTING, detail);
        try {
            startLeadLoop(sessionId, agentId, userMessage, chatModel, chatOptions, inputMessage);
        } catch (Exception e) {
            executionStateService.setStatus(
                    leadInstanceId,
                    AgentExecutionStatus.FAILED,
                    "lead continue failed: " + e.getMessage()
            );
            log.error("[TeamLeadAgent] Failed to continue lead loop: sessionId={}", sessionId, e);
        }
    }

    private List<TeamMessage> safeReadLeadInbox(String leadInstanceId) {
        try {
            return inboxService.readInbox(leadInstanceId);
        } catch (Exception e) {
            log.warn("[TeamLeadAgent] Failed to read lead inbox at round boundary: instanceId={}", leadInstanceId, e);
            return List.of();
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

    private String sanitizeLeadChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        // 过滤函数调用控制标记，避免前端展示内部 token。
        return chunk.replaceAll("<\\|[^|]+\\|>", "");
    }
}
