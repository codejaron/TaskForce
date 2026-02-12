package com.agent.domain.team.lead;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.agent.interceptor.ContextEnrichingToolInterceptor;
import com.agent.infrastructure.agent.interceptor.EventPublishingToolInterceptor;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.service.AgentService;
import com.agent.service.SessionExecutionTracker;
import com.agent.service.SessionService;
import com.agent.service.ToolCallService;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final TeamLeadToolProvider toolProvider;
    private final EventBus eventBus;
    private final SessionExecutionTracker executionTracker;
    private final InboxService inboxService;
    private final SessionService sessionService;
    private final AgentService agentService;
    private final ToolCallService toolCallService;

    private static final int MAX_REACT_ITERATIONS = 50; // Lead 需要更多迭代次数


    /**
     * 启动 Lead 的持久 ReAct 循环
     *
     * @param sessionId     会话 ID
     * @param agentId       Agent ID
     * @param userMessage   用户消息
     * @param chatModel     聊天模型
     * @param chatOptions   模型选项
     * @return Disposable 用于取消执行
     */
    public Disposable startLeadLoop(
            String sessionId,
            Long agentId,
            String userMessage,
            ChatModel chatModel,
            OpenAiChatOptions chatOptions) {

        log.info("[TeamLeadAgent] Starting Lead loop: sessionId={}, agentId={}", sessionId, agentId);

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
                    + "\n重要： spawn_worker 的 agentId 必须使用上面的数字 ID，不要编造。\n";

            // 2. 获取 Lead 工具
            List<ToolCallback> leadTools = toolProvider.getLeadTools();
            log.info("[TeamLeadAgent] Loaded {} Lead tools", leadTools.size());

            // 3. 构建 ReactAgent（添加 interceptor 传递 sessionId）
            ContextEnrichingToolInterceptor contextInterceptor = new ContextEnrichingToolInterceptor(sessionId, null);
            AtomicInteger sequenceCounter = new AtomicInteger(0);
            EventPublishingToolInterceptor eventInterceptor = new EventPublishingToolInterceptor(
                    sessionId, null, null, agentId, eventBus, toolCallService, sequenceCounter, null
            );
            ReactAgent reactAgent = ReactAgent.builder()
                    .name("TeamLead")
                    .model(chatModel)
                    .chatOptions(chatOptions)
                    .systemPrompt(systemPrompt)
                    .tools(leadTools)
                    .interceptors(contextInterceptor,eventInterceptor)
                    .build();

            // 3. 配置 RunnableConfig（传递 sessionId 到 metadata）
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId + "_lead")
                    .addMetadata("sessionId", sessionId)
                    .build();

            // 4. 启动流式执行
            AtomicBoolean completed = new AtomicBoolean(false);
            StringBuilder response = new StringBuilder();

            Disposable disposable = reactAgent.stream("", config)
                    .doOnNext(nodeOutput -> {
                        if (nodeOutput instanceof StreamingOutput streamingOutput) {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                response.append(chunk);
                                log.debug("[TeamLeadAgent] Received chunk: {}", chunk);
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        completed.set(true);
                        log.info("[TeamLeadAgent] Lead loop completed: sessionId={}", sessionId);
                    })
                    .doOnError(e -> {
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
}
