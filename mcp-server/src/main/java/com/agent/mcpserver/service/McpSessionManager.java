package com.agent.mcpserver.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 会话管理器
 * 管理客户端 SSE 连接和消息推送
 */
@Slf4j
@Service
public class McpSessionManager {

    /**
     * 会话信息
     */
    public record SessionInfo(
            String sessionId,
            String messageEndpoint,
            Sinks.Many<String> eventSink,
            long createdAt
    ) {}

    /**
     * 活跃会话：sessionId -> SessionInfo
     */
    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    /**
     * 创建新会话
     */
    public SessionInfo createSession() {
        String sessionId = UUID.randomUUID().toString();
        String messageEndpoint = "/message?sessionId=" + sessionId;
        
        // 创建事件发送器
        Sinks.Many<String> eventSink = Sinks.many().multicast().onBackpressureBuffer();

        SessionInfo session = new SessionInfo(
                sessionId,
                messageEndpoint,
                eventSink,
                System.currentTimeMillis()
        );

        activeSessions.put(sessionId, session);
        log.info("[SessionManager] Created session: {}", sessionId);

        return session;
    }

    /**
     * 获取会话
     */
    public SessionInfo getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId) {
        SessionInfo session = activeSessions.remove(sessionId);
        if (session != null) {
            session.eventSink().tryEmitComplete();
            log.info("[SessionManager] Closed session: {}", sessionId);
        }
    }

    /**
     * 发送事件到会话
     */
    public boolean sendEvent(String sessionId, String event) {
        SessionInfo session = activeSessions.get(sessionId);
        if (session == null) {
            log.warn("[SessionManager] Session not found: {}", sessionId);
            return false;
        }

        Sinks.EmitResult result = session.eventSink().tryEmitNext(event);
        return result.isSuccess();
    }

    /**
     * 获取会话的事件流
     */
    public Flux<String> getEventStream(String sessionId) {
        SessionInfo session = activeSessions.get(sessionId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }
        return session.eventSink().asFlux();
    }

    /**
     * 检查会话是否活跃
     */
    public boolean isSessionActive(String sessionId) {
        return activeSessions.containsKey(sessionId);
    }

    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    @PreDestroy
    public void cleanup() {
        log.info("[SessionManager] Cleaning up {} sessions", activeSessions.size());
        activeSessions.values().forEach(session -> session.eventSink().tryEmitComplete());
        activeSessions.clear();
    }
}
