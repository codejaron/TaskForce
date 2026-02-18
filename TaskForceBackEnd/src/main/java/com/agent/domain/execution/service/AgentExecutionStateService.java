package com.agent.domain.execution.service;

import com.agent.domain.execution.model.AgentExecutionState;
import com.agent.domain.execution.model.AgentExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionStateService {

    private static final String KEY_PREFIX = "agent:state:";
    private static final Duration STATE_TTL = Duration.ofHours(12);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String LUA_SET_STATUS = """
            local key = KEYS[1]
            local target = ARGV[1]
            local detail = ARGV[2]
            local now = ARGV[3]
            local instanceId = ARGV[4]
            local currentJson = redis.call('GET', key)
            local state = {}
            if currentJson and currentJson ~= '' then
              state = cjson.decode(currentJson)
            end
            state.instanceId = instanceId
            state.status = target
            state.detail = detail
            state.updatedAt = now
            redis.call('SET', key, cjson.encode(state), 'EX', 43200)
            return 1
            """;

    private static final String LUA_TRANSITION_IF = """
            local key = KEYS[1]
            local expected = ARGV[1]
            local target = ARGV[2]
            local detail = ARGV[3]
            local now = ARGV[4]
            local json = redis.call('GET', key)
            if not json or json == '' then
              return 0
            end
            local state = cjson.decode(json)
            if state.status ~= expected then
              return 0
            end
            state.status = target
            state.detail = detail
            state.updatedAt = now
            redis.call('SET', key, cjson.encode(state), 'EX', 43200)
            return 1
            """;

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
        AgentExecutionState current = getState(instanceId);
        AgentExecutionStatus fromStatus = current == null ? null : current.getStatus();

        try {
            redisTemplate.execute(
                    RedisScript.of(LUA_SET_STATUS, Long.class),
                    java.util.List.of(buildKey(instanceId)),
                    status.name(),
                    detail == null ? "" : detail,
                    LocalDateTime.now().toString(),
                    instanceId
            );
            log.info(
                    "[AgentExecutionStateService] Transition applied: instanceId={}, from={}, to={}, trigger=setStatus, detail={}, result=APPLIED",
                    instanceId, fromStatus, status, detail
            );
        } catch (Exception e) {
            log.error("[AgentExecutionStateService] Failed to set status: instanceId={}, to={}, detail={}",
                    instanceId, status, detail, e);
        }
    }

    public boolean transitionIf(String instanceId,
                                AgentExecutionStatus expectedStatus,
                                AgentExecutionStatus targetStatus,
                                String detail) {
        AgentExecutionState current = getState(instanceId);
        AgentExecutionStatus currentStatus = current == null ? null : current.getStatus();

        try {
            Long result = redisTemplate.execute(
                    RedisScript.of(LUA_TRANSITION_IF, Long.class),
                    java.util.List.of(buildKey(instanceId)),
                    expectedStatus == null ? "" : expectedStatus.name(),
                    targetStatus.name(),
                    detail == null ? "" : detail,
                    LocalDateTime.now().toString()
            );
            boolean applied = result != null && result == 1L;
            if (!applied) {
                log.info(
                        "[AgentExecutionStateService] Transition rejected: instanceId={}, from={}, expected={}, to={}, trigger=transitionIf, detail={}, result=REJECTED",
                        instanceId, currentStatus, expectedStatus, targetStatus, detail
                );
                return false;
            }
            log.info(
                    "[AgentExecutionStateService] Transition applied: instanceId={}, from={}, to={}, expected={}, trigger=transitionIf, detail={}, result=APPLIED",
                    instanceId, currentStatus, targetStatus, expectedStatus, detail
            );
            return true;
        } catch (Exception e) {
            log.error(
                    "[AgentExecutionStateService] Transition failed: instanceId={}, from={}, expected={}, to={}, detail={}",
                    instanceId, currentStatus, expectedStatus, targetStatus, detail, e
            );
            return false;
        }
    }

    private String buildKey(String instanceId) {
        return KEY_PREFIX + instanceId;
    }
}
