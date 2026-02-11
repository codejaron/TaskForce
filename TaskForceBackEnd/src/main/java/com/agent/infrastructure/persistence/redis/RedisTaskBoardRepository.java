package com.agent.infrastructure.persistence.redis;

import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;
import com.agent.domain.taskboard.repository.TaskBoardRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务板 Redis 仓储实现
 * 键结构：taskboard:{sessionId}，使用 Hash 结构
 * 每个 field 是 task:{taskId}，值是 Task 的 JSON
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisTaskBoardRepository implements TaskBoardRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "taskboard:";
    private static final String FIELD_PREFIX = "task:";

    @Override
    public void save(Task task) {
        try {
            String key = buildKey(task.getSessionId());
            String field = buildField(task.getTaskId());
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForHash().put(key, field, json);
            log.debug("Saved task: {} in session: {}", task.getTaskId(), task.getSessionId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Task: {}", task.getTaskId(), e);
            throw new RuntimeException("Failed to save Task", e);
        }
    }

    @Override
    public void saveAll(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        try {
            // 按 sessionId 分组
            Map<String, List<Task>> tasksBySession = tasks.stream()
                    .collect(Collectors.groupingBy(Task::getSessionId));

            for (Map.Entry<String, List<Task>> entry : tasksBySession.entrySet()) {
                String key = buildKey(entry.getKey());
                Map<String, String> taskMap = entry.getValue().stream()
                        .collect(Collectors.toMap(
                                task -> buildField(task.getTaskId()),
                                task -> {
                                    try {
                                        return objectMapper.writeValueAsString(task);
                                    } catch (JsonProcessingException e) {
                                        throw new RuntimeException("Failed to serialize Task", e);
                                    }
                                }
                        ));

                redisTemplate.opsForHash().putAll(key, taskMap);
            }

            log.debug("Saved {} tasks", tasks.size());
        } catch (Exception e) {
            log.error("Failed to save tasks", e);
            throw new RuntimeException("Failed to save tasks", e);
        }
    }

    @Override
    public Optional<Task> findById(String sessionId, int taskId) {
        try {
            String key = buildKey(sessionId);
            String field = buildField(taskId);
            String json = (String) redisTemplate.opsForHash().get(key, field);
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, Task.class));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to find Task: sessionId={}, taskId={}", sessionId, taskId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<Task> findBySessionId(String sessionId) {
        try {
            String key = buildKey(sessionId);
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

            if (entries.isEmpty()) {
                return new ArrayList<>();
            }

            List<Task> tasks = new ArrayList<>();
            for (Object value : entries.values()) {
                String json = (String) value;
                Task task = objectMapper.readValue(json, Task.class);
                tasks.add(task);
            }

            return tasks;
        } catch (Exception e) {
            log.error("Failed to find Tasks by sessionId: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<Task> findBySessionIdAndStatus(String sessionId, TaskStatus status) {
        return findBySessionId(sessionId).stream()
                .filter(task -> task.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findBySessionIdAndOwner(String sessionId, String owner) {
        return findBySessionId(sessionId).stream()
                .filter(task -> owner.equals(task.getOwner()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findExecutableTasks(String sessionId) {
        return findBySessionId(sessionId).stream()
                .filter(task -> (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.CLAIMED)
                        && task.canStart())
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String sessionId, int taskId) {
        try {
            String key = buildKey(sessionId);
            String field = buildField(taskId);
            Long deleted = redisTemplate.opsForHash().delete(key, field);
            if (deleted > 0) {
                log.debug("Deleted task: sessionId={}, taskId={}", sessionId, taskId);
            }
        } catch (Exception e) {
            log.error("Failed to delete Task: sessionId={}, taskId={}", sessionId, taskId, e);
            throw new RuntimeException("Failed to delete Task", e);
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        try {
            String key = buildKey(sessionId);
            redisTemplate.delete(key);
            log.debug("Deleted all tasks for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to delete Tasks by sessionId: {}", sessionId, e);
            throw new RuntimeException("Failed to delete Tasks", e);
        }
    }

    @Override
    public boolean exists(String sessionId, int taskId) {
        try {
            String key = buildKey(sessionId);
            String field = buildField(taskId);
            return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(key, field));
        } catch (Exception e) {
            log.error("Failed to check existence of Task: sessionId={}, taskId={}", sessionId, taskId, e);
            return false;
        }
    }

    /**
     * 构建 Redis 键
     */
    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    /**
     * 构建 Hash field
     */
    private String buildField(int taskId) {
        return FIELD_PREFIX + taskId;
    }
}
