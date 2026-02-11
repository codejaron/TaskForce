package com.agent.domain.team.lead;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.agent.interceptor.ContextEnrichingToolInterceptor;
import com.agent.infrastructure.event.EventBus;
import com.agent.service.SessionExecutionTracker;
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
    private final TeamLeadToolProvider toolProvider;
    private final EventBus eventBus;
    private final SessionExecutionTracker executionTracker;
    private final InboxService inboxService;

    private static final int MAX_REACT_ITERATIONS = 50; // Lead 需要更多迭代次数
    private static final String LEAD_SYSTEM_PROMPT = """
        You are a Team Lead Agent responsible for coordinating a team of worker agents.

        Your responsibilities:
        1. Break down user requests into tasks using create_task
        2. Spawn workers using spawn_worker when needed
        3. Monitor task progress using list_tasks
        4. Communicate with workers using send_message or broadcast
        5. Check your inbox regularly using read_inbox
        6. Reply to users using reply_user
        7. Manage team members using list_teammates and shutdown_worker

        Work autonomously to achieve the user's goals by coordinating your team effectively.
        """;

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
            // 1. 获取 Lead 工具
            List<ToolCallback> leadTools = toolProvider.getLeadTools();
            log.info("[TeamLeadAgent] Loaded {} Lead tools", leadTools.size());

            // 2. 构建 ReactAgent（添加 interceptor 传递 sessionId）
            ContextEnrichingToolInterceptor contextInterceptor = new ContextEnrichingToolInterceptor(sessionId, null);

            ReactAgent reactAgent = ReactAgent.builder()
                    .name("TeamLead")
                    .model(chatModel)
                    .chatOptions(chatOptions)
                    .systemPrompt(LEAD_SYSTEM_PROMPT)
                    .instruction(userMessage)
                    .tools(leadTools)
                    .interceptors(contextInterceptor)
                    .build();

            // 3. 配置 RunnableConfig（传递 sessionId 到 metadata）
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId + "_lead")
                    .addMetadata("sessionId", sessionId)
                    .build();

            // 4. 启动流式执行
            AtomicBoolean completed = new AtomicBoolean(false);
            StringBuilder response = new StringBuilder();

            Disposable disposable = reactAgent.stream(userMessage, config)
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
}
