package com.agent.domain.execution.service;

import com.agent.domain.execution.model.AgentExecutionState;
import com.agent.domain.execution.model.AgentExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionStateService {

    private static final String KEY_PREFIX = "agent:state:";
    private static final Duration STATE_TTL = Duration.ofHours(12);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public AgentExecutionStatus getStatus(String instanceId) {
        AgentExecutionState state = getState(instanceId);
        return state == null ? null : state.getStatus();
    }

    public AgentExecutionState getState(String instanceId) {
        try {
            String json = redisTemplate.opsForValue().get(buildKey(instanceId));
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, AgentExecutionState.class);
        } catch (Exception e) {
            log.warn("[AgentExecutionStateService] Failed to read state: instanceId={}", instanceId, e);
            return null;
        }
    }

    public void setStatus(String instanceId, AgentExecutionStatus status, String detail) {
        AgentExecutionState state = AgentExecutionState.builder()
                .instanceId(instanceId)
                .status(status)
                .detail(detail)
                .updatedAt(LocalDateTime.now())
                .build();
        writeState(instanceId, state);
    }

    public boolean transitionIf(String instanceId,
                                AgentExecutionStatus expectedStatus,
                                AgentExecutionStatus targetStatus,
                                String detail) {
        Object lock = locks.computeIfAbsent(instanceId, key -> new Object());
        synchronized (lock) {
            AgentExecutionState current = getState(instanceId);
            AgentExecutionStatus currentStatus = current == null ? null : current.getStatus();

            if (currentStatus != expectedStatus) {
                return false;
            }

            setStatus(instanceId, targetStatus, detail);
            return true;
        }
    }

    private void writeState(String instanceId, AgentExecutionState state) {
        try {
            String key = buildKey(instanceId);
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, STATE_TTL);
        } catch (Exception e) {
            log.error("[AgentExecutionStateService] Failed to write state: instanceId={}", instanceId, e);
        }
    }

    private String buildKey(String instanceId) {
        return KEY_PREFIX + instanceId;
    }
}
