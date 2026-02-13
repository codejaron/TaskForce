package com.agent.domain.execution.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionWaitIntentService {

    private static final String KEY_PREFIX = "agent:wait-intent:";
    private static final Duration INTENT_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;

    public void markWaitingReply(String instanceId, String reason) {
        String value = reason == null || reason.isBlank() ? LocalDateTime.now().toString()
                : reason + "|" + LocalDateTime.now();
        redisTemplate.opsForValue().set(buildKey(instanceId), value, INTENT_TTL);
    }

    public boolean consumeWaitingReply(String instanceId) {
        String key = buildKey(instanceId);
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            redisTemplate.delete(key);
        }
        return value != null;
    }

    public void clear(String instanceId) {
        redisTemplate.delete(buildKey(instanceId));
    }

    private String buildKey(String instanceId) {
        return KEY_PREFIX + instanceId;
    }
}
