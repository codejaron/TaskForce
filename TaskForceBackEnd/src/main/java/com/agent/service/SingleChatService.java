package com.agent.service;

import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.ChatCompleteEvent;
import com.agent.infrastructure.event.events.ChatDeltaEvent;
import com.agent.infrastructure.event.events.ChatErrorEvent;
import com.agent.infrastructure.persistence.entity.SessionAgent;
import com.agent.infrastructure.sandbox.SessionSandboxManager;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * 单聊服务
 * 提供单个 Agent 的流式对话功能
 */
@Slf4j
@Service
public class SingleChatService {

    private final ReactAgentFactory reactAgentFactory;
    private final EventBus eventBus;
    private final SessionService sessionService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TokenUsageService tokenUsageService;
    private final SessionSandboxManager sessionSandboxManager;

    // 缓存 ReactAgent + agentId（使用 ConcurrentHashMap）
    private final Map<String, ChatAgentContext> agentCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(30);

    public SingleChatService(
            ReactAgentFactory reactAgentFactory,
            EventBus eventBus,
            SessionService sessionService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TokenUsageService tokenUsageService,
            @Autowired(required = false) SessionSandboxManager sessionSandboxManager) {
        this.reactAgentFactory = reactAgentFactory;
        this.eventBus = eventBus;
        this.sessionService = sessionService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tokenUsageService = tokenUsageService;
        this.sessionSandboxManager = sessionSandboxManager;
    }

    /**
     * 单聊流式响应
     */
    public Flux<ServerSentEvent<String>> chat(String sessionId, String userMessage) {
        String lockKey = "chat:lock:" + sessionId;
        String roundId = UUID.randomUUID().toString();
        AtomicBoolean flushed = new AtomicBoolean(false);

        // 1. Redis SETNX 加锁（防止并发请求）
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            return Flux.error(new IllegalStateException("会话正在处理中，请稍后再试"));
        }

        return Flux.defer(() -> {
            try {
                if (sessionSandboxManager != null) {
                    sessionSandboxManager.beginRound(sessionId, roundId);
                }

                // 2. 获取或创建 ReactAgent
                ChatAgentContext agentContext = getOrCreateAgent(sessionId);
                ReactAgent agent = agentContext.agent();
                TokenUsageStreamTracker usageTracker = new TokenUsageStreamTracker(
                        tokenUsageService,
                        sessionId,
                        agentContext.agentId()
                );

                // 3. 配置 RunnableConfig
                RunnableConfig config = RunnableConfig.builder()
                        .threadId(sessionId)
                        .addMetadata("sessionId", sessionId)
                        .addMetadata("roundId", roundId)
                        .build();

                // 4. 调用 ReactAgent.stream()
                Flux<NodeOutput> outputFlux = agent.stream(userMessage, config);

                // 5. 订阅 EventBus 获取工具调用事件
                Flux<ServerSentEvent<String>> toolEventFlux = eventBus.subscribe(sessionId)
                        .filter(event -> {
                            String eventType = event.getEventType();
                            return "tool_call_start".equals(eventType) ||
                                   "tool_call_complete".equals(eventType);
                        })
                        .map(event -> ServerSentEvent.<String>builder()
                                .event(event.getEventType())
                                .data(event.toJson())
                                .build());

                // 6. 收集流式输出
                StringBuilder responseBuilder = new StringBuilder();

                Flux<ServerSentEvent<String>> chatFlux = outputFlux
                        .flatMap(output -> {
                            usageTracker.accept(output);

                            if (output instanceof StreamingOutput streamingOutput) {
                                String chunk = streamingOutput.chunk();

                                // 跳过 null 或空的 chunk
                                if (chunk == null || chunk.isEmpty()) {
                                    return Flux.empty();
                                }

                                responseBuilder.append(chunk);

                                // 发布增量事件
                                publishDeltaEvent(sessionId, chunk);

                                // 返回 SSE
                                return Flux.just(ServerSentEvent.<String>builder()
                                        .event("chat_delta")
                                        .data(toJson(Map.of("delta", chunk)))
                                        .build());
                            }
                            return Flux.empty();
                        })
                        .concatWith(Flux.defer(() -> {
                            // 先完成文件 flush，成功后再发布 complete 事件
                            flushRoundOnce(sessionId, roundId, flushed);
                            publishCompleteEvent(sessionId);

                            return Flux.just(ServerSentEvent.<String>builder()
                                    .event("chat_complete")
                                    .data(toJson(Map.of("status", "completed", "syncStatus", SessionSandboxManager.SYNCED)))
                                    .build());
                        }));

                // 7. 合并聊天流和工具事件流
                return Flux.merge(chatFlux, toolEventFlux)
                        .onErrorResume(e -> {
                            log.error("[SingleChat] Error: sessionId={}", sessionId, e);
                            publishErrorEvent(sessionId, e.getMessage());
                            return Flux.just(ServerSentEvent.<String>builder()
                                    .event("chat_error")
                                    .data(toJson(Map.of(
                                            "status", "error",
                                            "error", e.getMessage(),
                                            "syncStatus", SessionSandboxManager.SYNC_FAILED
                                    )))
                                    .build());
                        })
                        .doFinally(signalType -> {
                            try {
                                flushRoundOnce(sessionId, roundId, flushed);
                            } catch (Exception e) {
                                log.warn("[SingleChat] Final flush failed: sessionId={}, roundId={}, err={}",
                                        sessionId, roundId, e.getMessage());
                            }
                            // 释放锁（使用 DEL，不会抛异常）
                            try {
                                redisTemplate.delete(lockKey);
                                log.debug("[SingleChat] Lock released: sessionId={}", sessionId);
                            } catch (Exception e) {
                                log.warn("[SingleChat] Failed to release lock: sessionId={}", sessionId, e);
                            }
                        });

            } catch (Exception e) {
                // 异常时也要释放锁
                try {
                    flushRoundOnce(sessionId, roundId, flushed);
                    redisTemplate.delete(lockKey);
                } catch (Exception ex) {
                    log.warn("[SingleChat] Failed to release lock on error", ex);
                }
                return Flux.error(e);
            }
        });
    }

    /**
     * 获取或创建 ReactAgent（带缓存）
     */
    private ChatAgentContext getOrCreateAgent(String sessionId) {
        // 检查缓存是否过期
        Long timestamp = cacheTimestamps.get(sessionId);
        if (timestamp != null && System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) {
            // 缓存过期，清除
            agentCache.remove(sessionId);
            cacheTimestamps.remove(sessionId);
        }

        // 从缓存获取或创建新的 ReactAgent
        return agentCache.computeIfAbsent(sessionId, key -> {
            // 从 Session 获取 agentId
            List<SessionAgent> agents = sessionService.getSessionAgents(sessionId);

            if (agents.isEmpty()) {
                throw new RuntimeException("No agent found in session: " + sessionId);
            }

            Long agentId = agents.get(0).getAgentId();

            // 构建 ReactAgent（DbChatMemory 会自动从 DB 加载历史）
            log.info("[SingleChat] Creating ReactAgent for session: {}, agentId: {}", sessionId, agentId);

            // 记录缓存时间戳
            cacheTimestamps.put(sessionId, System.currentTimeMillis());

            ReactAgent reactAgent = reactAgentFactory.buildChatReactAgent(agentId, sessionId);
            return new ChatAgentContext(reactAgent, agentId);
        });
    }

    /**
     * 发布增量事件
     */
    private void publishDeltaEvent(String sessionId, String delta) {
        try {
            ChatDeltaEvent event = new ChatDeltaEvent(sessionId, delta);
            eventBus.publish(sessionId, event);
        } catch (Exception e) {
            log.warn("[SingleChat] Failed to publish delta event: sessionId={}", sessionId, e);
        }
    }

    /**
     * 发布完成事件
     */
    private void publishCompleteEvent(String sessionId) {
        try {
            ChatCompleteEvent event = new ChatCompleteEvent(sessionId);
            eventBus.publish(sessionId, event);
            log.info("[SingleChat] Chat completed: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("[SingleChat] Failed to publish complete event: sessionId={}", sessionId, e);
        }
    }

    /**
     * 发布错误事件
     */
    private void publishErrorEvent(String sessionId, String errorMessage) {
        try {
            ChatErrorEvent event = new ChatErrorEvent(sessionId, errorMessage);
            eventBus.publish(sessionId, event);
        } catch (Exception e) {
            log.warn("[SingleChat] Failed to publish error event: sessionId={}", sessionId, e);
        }
    }

    /**
     * 转换为 JSON 字符串
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[SingleChat] Failed to convert to JSON", e);
            return "{}";
        }
    }

    /**
     * 清除会话缓存
     */
    public void clearCache(String sessionId) {
        agentCache.remove(sessionId);
        cacheTimestamps.remove(sessionId);
        log.info("[SingleChat] Cache cleared for session: {}", sessionId);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        agentCache.clear();
        cacheTimestamps.clear();
        log.info("[SingleChat] All cache cleared");
    }

    private void flushRoundOnce(String sessionId, String roundId, AtomicBoolean flushed) {
        if (flushed != null && !flushed.compareAndSet(false, true)) {
            return;
        }
        if (sessionSandboxManager == null) {
            return;
        }
        boolean synced = sessionSandboxManager.flushRound(sessionId, roundId);
        if (!synced) {
            throw new IllegalStateException("Round flush failed: sessionId=" + sessionId + ", roundId=" + roundId);
        }
    }

    private record ChatAgentContext(ReactAgent agent, Long agentId) {}
}
