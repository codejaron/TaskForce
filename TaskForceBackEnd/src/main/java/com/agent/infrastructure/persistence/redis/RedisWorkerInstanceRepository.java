package com.agent.infrastructure.persistence.redis;

import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.model.WorkerStatus;
import com.agent.domain.worker.repository.WorkerInstanceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Worker 实例 Redis 仓储实现
 * 键结构：worker:{sessionId}:{instanceId}
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisWorkerInstanceRepository implements WorkerInstanceRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "worker:";

    @Override
    public WorkerInstance save(WorkerInstance instance) {
        try {
            String key = buildKey(instance.getSessionId(), instance.getInstanceId());
            String json = objectMapper.writeValueAsString(instance);
            redisTemplate.opsForValue().set(key, json);
            log.debug("Saved worker instance: {}", key);
            return instance;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize WorkerInstance: {}", instance.getInstanceId(), e);
            throw new RuntimeException("Failed to save WorkerInstance", e);
        }
    }

    @Override
    public Optional<WorkerInstance> findById(String instanceId) {
        try {
            // 需要扫描所有 worker:* 键来查找
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*:" + instanceId);
            if (keys == null || keys.isEmpty()) {
                return Optional.empty();
            }

            String key = keys.iterator().next();
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }

            WorkerInstance instance = objectMapper.readValue(json, WorkerInstance.class);
            return Optional.of(instance);
        } catch (Exception e) {
            log.error("Failed to find WorkerInstance by id: {}", instanceId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<WorkerInstance> findBySessionId(String sessionId) {
        try {
            String pattern = KEY_PREFIX + sessionId + ":*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys == null || keys.isEmpty()) {
                return new ArrayList<>();
            }

            List<WorkerInstance> instances = new ArrayList<>();
            for (String key : keys) {
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    WorkerInstance instance = objectMapper.readValue(json, WorkerInstance.class);
                    instances.add(instance);
                }
            }

            return instances;
        } catch (Exception e) {
            log.error("Failed to find WorkerInstances by sessionId: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void delete(String instanceId) {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*:" + instanceId);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("Deleted worker instance: {}", instanceId);
            }
        } catch (Exception e) {
            log.error("Failed to delete WorkerInstance: {}", instanceId, e);
            throw new RuntimeException("Failed to delete WorkerInstance", e);
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        try {
            String pattern = KEY_PREFIX + sessionId + ":*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("Deleted all worker instances for session: {}", sessionId);
            }
        } catch (Exception e) {
            log.error("Failed to delete WorkerInstances by sessionId: {}", sessionId, e);
            throw new RuntimeException("Failed to delete WorkerInstances", e);
        }
    }

    @Override
    public boolean existsById(String instanceId) {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*:" + instanceId);
            return keys != null && !keys.isEmpty();
        } catch (Exception e) {
            log.error("Failed to check existence of WorkerInstance: {}", instanceId, e);
            return false;
        }
    }

    /**
     * 构建 Redis 键
     */
    private String buildKey(String sessionId, String instanceId) {
        return KEY_PREFIX + sessionId + ":" + instanceId;
    }
}
