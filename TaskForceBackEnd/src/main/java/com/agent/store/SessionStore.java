package com.agent.store;

import com.agent.entity.Session;
import com.agent.entity.SessionAgent;
import com.agent.mapper.SessionAgentMapper;
import com.agent.mapper.SessionMapper;
import com.agent.model.AgentProfile;
import com.agent.model.SessionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 会话状态存储（数据库持久化 + 内存缓存）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStore {

    private final SessionMapper sessionMapper;
    private final SessionAgentMapper sessionAgentMapper;
    private final ObjectMapper objectMapper;
    
    // 内存缓存，提升性能
    private final Map<String, SessionState> cache = new ConcurrentHashMap<>();

    /**
     * 保存会话状态
     */
    public SessionState save(SessionState sessionState) {
        try {
            if (sessionState.getSessionId() == null || sessionState.getSessionId().isEmpty()) {
                sessionState.setSessionId(UUID.randomUUID().toString());
            }
            
            // 转换为数据库实体
            Session session = convertToEntity(sessionState);
            
            // 保存到数据库
            if (sessionMapper.selectById(session.getId()) != null) {
                sessionMapper.updateById(session);
            } else {
                sessionMapper.insert(session);
                
                // 保存关联的 Agents
                saveSessionAgents(sessionState);
            }
            
            // 更新缓存
            cache.put(sessionState.getSessionId(), sessionState);
            
            log.info("Session saved to database: {}", sessionState.getSessionId());
            return sessionState;
        } catch (Exception e) {
            log.error("Failed to save session to database, fallback to memory only", e);
            cache.put(sessionState.getSessionId(), sessionState);
            return sessionState;
        }
    }

    /**
     * 根据 ID 查找
     */
    public Optional<SessionState> findById(String sessionId) {
        // 先从缓存查找
        if (cache.containsKey(sessionId)) {
            return Optional.of(cache.get(sessionId));
        }
        
        try {
            // 从数据库查找
            Session session = sessionMapper.selectById(sessionId);
            if (session != null) {
                SessionState sessionState = convertToSessionState(session);
                cache.put(sessionId, sessionState);
                return Optional.of(sessionState);
            }
        } catch (Exception e) {
            log.error("Failed to load session from database", e);
        }
        
        return Optional.empty();
    }

    /**
     * 获取所有会话
     */
    public List<SessionState> findAll() {
        try {
            // 从数据库获取
            List<Session> sessions = sessionMapper.selectList(null);
            return sessions.stream()
                    .map(this::convertToSessionState)
                    .peek(s -> cache.put(s.getSessionId(), s))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to load sessions from database, using cache", e);
            return new ArrayList<>(cache.values());
        }
    }

    /**
     * 删除会话
     */
    public void deleteById(String sessionId) {
        try {
            sessionMapper.deleteById(sessionId);
            log.info("Session deleted from database: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to delete session from database", e);
        }
        cache.remove(sessionId);
    }

    /**
     * 检查是否存在
     */
    public boolean existsById(String sessionId) {
        return cache.containsKey(sessionId) || sessionMapper.selectById(sessionId) != null;
    }
    
    /**
     * 将 SessionState 转换为数据库实体
     */
    private Session convertToEntity(SessionState state) throws Exception {
        String configJson = objectMapper.writeValueAsString(Map.of(
                "mode", state.getOrchestrationMode() != null ? state.getOrchestrationMode().toString() : "ROUND_ROBIN",
                "maxTurns", state.getMaxTurns(),
                "currentTurn", state.getCurrentTurn()
        ));
        
        return Session.builder()
                .id(state.getSessionId())
                .name(state.getName())
                .type("GROUP")
                .status(state.getStatus() != null ? state.getStatus().toString() : "CREATED")
                .config(configJson)
                .maxRounds(state.getMaxTurns())
                .currentRound(state.getCurrentTurn())
                .build();
    }
    
    /**
     * 将数据库实体转换为 SessionState
     */
    private SessionState convertToSessionState(Session session) {
        // TODO: 加载关联的 Agents
        List<AgentProfile> agents = new ArrayList<>();
        
        SessionState.OrchestrationMode mode = SessionState.OrchestrationMode.ROUND_ROBIN;
        try {
            // try to parse mode from session.config (which may be JSON)
            if (session.getConfig() != null && !session.getConfig().isEmpty()) {
                Map<String, Object> cfg = objectMapper.readValue(session.getConfig(), Map.class);
                Object m = cfg.get("mode");
                if (m != null) {
                    try { mode = SessionState.OrchestrationMode.valueOf(m.toString()); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse session config mode for session {}: {}", session.getId(), e.getMessage());
        }

        SessionState.Status status = SessionState.Status.CREATED;
        try {
            if (session.getStatus() != null && !session.getStatus().isEmpty()) {
                status = SessionState.Status.valueOf(session.getStatus());
            }
        } catch (IllegalArgumentException iae) {
            log.warn("Unknown session status '{}' for session {}, falling back to CREATED", session.getStatus(), session.getId());
            status = SessionState.Status.CREATED;
        }

        return SessionState.builder()
                .sessionId(session.getId())
                .name(session.getName())
                .agents(agents)
                .orchestrationMode(mode)
                .maxTurns(session.getMaxRounds())
                .currentTurn(session.getCurrentRound())
                .status(status)
                .build();
    }
    
    /**
     * 保存会话关联的 Agents
     */
    private void saveSessionAgents(SessionState sessionState) {
        if (sessionState.getAgents() == null) {
            return;
        }
        
        int order = 0;
        for (AgentProfile agent : sessionState.getAgents()) {
            try {
                Long agentId = Long.parseLong(agent.getId());
                SessionAgent sessionAgent = SessionAgent.builder()
                        .sessionId(sessionState.getSessionId())
                        .agentId(agentId)
                        .joinOrder(order++)
                        .isAdmin(false)
                        .build();
                
                sessionAgentMapper.insert(sessionAgent);
            } catch (Exception e) {
                log.error("Failed to save session agent: {}", agent.getId(), e);
            }
        }
    }
}
