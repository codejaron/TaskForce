package com.agent.domain.taskboard.service;

import com.agent.domain.taskboard.dto.TaskUpdateRequest;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;
import com.agent.domain.taskboard.repository.TaskBoardRepository;
import com.agent.domain.taskboard.validator.TaskValidator;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.TaskClaimedEvent;
import com.agent.infrastructure.event.events.TaskCompletedEvent;
import com.agent.infrastructure.event.events.TaskCreatedEvent;
import com.agent.infrastructure.event.events.TaskUnblockedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务板领域服务
 * 负责任务的生命周期管理和依赖关系处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBoardService {

    private final TaskBoardRepository taskBoardRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final EventBus eventBus;

    private static final String KEY_PREFIX = "taskboard:";
    private static final String FIELD_PREFIX = "task:";

    /**
     * 创建任务
     */
    public Task createTask(String sessionId, String subject, String description, List<Integer> blockedBy) {
        // 分配递增序号：当前 session 最大 taskId + 1
        List<Task> existingTasks = taskBoardRepository.findBySessionId(sessionId);
        int nextId = existingTasks.stream()
                .mapToInt(Task::getTaskId)
                .max()
                .orElse(0) + 1;

        Task task = Task.builder()
                .taskId(nextId)
                .sessionId(sessionId)
                .subject(subject)
                .description(description)
                .blockedBy(blockedBy)
                .status(TaskStatus.PENDING)
                .build();

        // 验证依赖关系
        List<Task> allTasks = taskBoardRepository.findBySessionId(sessionId);
        allTasks.add(task);
        TaskValidator.ValidationResult result = TaskValidator.validate(allTasks);
        if (!result.isValid()) {
            throw new IllegalArgumentException("Invalid task dependencies: " + result.getErrorMessage());
        }

        // 维护双向依赖关系（更新 blocks）
        if (blockedBy != null && !blockedBy.isEmpty()) {
            for (int blockedByTaskId : blockedBy) {
                Task blockedByTask = taskBoardRepository.findById(sessionId, blockedByTaskId)
                        .orElseThrow(() -> new IllegalArgumentException("Blocked by task not found: " + blockedByTaskId));

                if (blockedByTask.getBlocks() == null) {
                    blockedByTask.setBlocks(new ArrayList<>());
                }
                if (!blockedByTask.getBlocks().contains(task.getTaskId())) {
                    blockedByTask.getBlocks().add(task.getTaskId());
                    taskBoardRepository.save(blockedByTask);
                }
            }
        }

        taskBoardRepository.save(task);

        // 发布 TaskCreatedEvent
        eventBus.publish(sessionId, new TaskCreatedEvent(sessionId, task.getTaskId(), subject, description));

        log.info("Created task: taskId={}, sessionId={}, subject={}", task.getTaskId(), sessionId, subject);
        return task;
    }

    /**
     * 认领任务（原子操作）
     * 使用 Lua 脚本确保只有一个 Worker 能成功认领
     */
    public boolean claimTask(String sessionId, int taskId, String owner) {
        try {
            String key = KEY_PREFIX + sessionId;
            String field = FIELD_PREFIX + taskId;

            // Lua 脚本：原子性地检查并更新任务状态
            String luaScript =
                "local taskJson = redis.call('HGET', KEYS[1], KEYS[2]) " +
                "if not taskJson then " +
                "  return 'NOT_FOUND' " +
                "end " +
                "local task = cjson.decode(taskJson) " +
                "if task.status ~= 'PENDING' then " +
                "  return 'INVALID_STATUS' " +
                "end " +
                "if task.owner and task.owner ~= '' then " +
                "  return 'ALREADY_CLAIMED' " +
                "end " +
                "task.status = 'CLAIMED' " +
                "task.owner = ARGV[1] " +
                "task.updatedAt = ARGV[2] " +
                "redis.call('HSET', KEYS[1], KEYS[2], cjson.encode(task)) " +
                "return 'SUCCESS'";

            RedisScript<String> script = RedisScript.of(luaScript, String.class);
            String result = redisTemplate.execute(
                script,
                Arrays.asList(key, field),
                owner,
                LocalDateTime.now().toString()
            );

            if ("SUCCESS".equals(result)) {
                // 发布 TaskClaimedEvent
                eventBus.publish(sessionId, new TaskClaimedEvent(sessionId, taskId, owner));
                log.info("Task claimed successfully: taskId={}, owner={}", taskId, owner);
                return true;
            } else {
                log.warn("Failed to claim task: taskId={}, owner={}, reason={}", taskId, owner, result);
                return false;
            }
        } catch (Exception e) {
            log.error("Error claiming task: taskId={}, owner={}", taskId, owner, e);
            return false;
        }
    }

    /**
     * 开始执行任务
     */
    public void startTask(String sessionId, int taskId) {
        Task task = taskBoardRepository.findById(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (task.getStatus() != TaskStatus.CLAIMED) {
            throw new IllegalStateException("Task must be claimed before starting: " + taskId);
        }

        task.start();
        taskBoardRepository.save(task);
        log.info("Task started: taskId={}", taskId);
    }

    /**
     * 完成任务（自动解锁下游任务）
     * 使用 Lua 脚本实现原子性操作
     */
    public void completeTask(String sessionId, int taskId) {
        Task task = taskBoardRepository.findById(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        String key = KEY_PREFIX + sessionId;
        String field = FIELD_PREFIX + taskId;

        // Lua 脚本：原子性地完成任务并解锁下游任务
        String luaScript =
            "local taskJson = redis.call('HGET', KEYS[1], KEYS[2]) " +
            "if not taskJson then return cjson.encode({error='NOT_FOUND'}) end " +
            "local task = cjson.decode(taskJson) " +
            "task.status = 'COMPLETED' " +
            "task.completedAt = ARGV[1] " +
            "task.updatedAt = ARGV[1] " +
            "redis.call('HSET', KEYS[1], KEYS[2], cjson.encode(task)) " +
            "local unblocked = {} " +
            "if task.blocks then " +
            "  for i, blockedTaskId in ipairs(task.blocks) do " +
            "    local blockedField = 'task:' .. blockedTaskId " +
            "    local blockedJson = redis.call('HGET', KEYS[1], blockedField) " +
            "    if blockedJson then " +
            "      local blockedTask = cjson.decode(blockedJson) " +
            "      if blockedTask.blockedBy then " +
            "        local newBlockedBy = {} " +
            "        for j, depId in ipairs(blockedTask.blockedBy) do " +
            "          if depId ~= ARGV[2] then " +
            "            table.insert(newBlockedBy, depId) " +
            "          end " +
            "        end " +
            "        blockedTask.blockedBy = newBlockedBy " +
            "        blockedTask.updatedAt = ARGV[1] " +
            "        redis.call('HSET', KEYS[1], blockedField, cjson.encode(blockedTask)) " +
            "        if #newBlockedBy == 0 then " +
            "          table.insert(unblocked, blockedTaskId) " +
            "        end " +
            "      end " +
            "    end " +
            "  end " +
            "end " +
            "return cjson.encode({success=true, unblocked=unblocked})";

        try {
            RedisScript<String> script = RedisScript.of(luaScript, String.class);
            String result = redisTemplate.execute(
                script,
                Arrays.asList(key, field),
                LocalDateTime.now().toString(),
                taskId
            );

            // 解析结果并发布事件
            if (result != null && result.contains("success")) {
                eventBus.publish(sessionId, new TaskCompletedEvent(sessionId, taskId, task.getOwner()));

                // 解析被解锁的任务列表
                if (result.contains("unblocked")) {
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> resultMap = objectMapper.readValue(result, java.util.Map.class);
                        @SuppressWarnings("unchecked")
                        List<String> unblockedTaskIds = (List<String>) resultMap.get("unblocked");

                        if (unblockedTaskIds != null) {
                            for (Object unblockedTaskIdObj : unblockedTaskIds) {
                                int unblockedTaskId = unblockedTaskIdObj instanceof Number
                                    ? ((Number) unblockedTaskIdObj).intValue()
                                    : Integer.parseInt(unblockedTaskIdObj.toString());
                                eventBus.publish(sessionId, new TaskUnblockedEvent(sessionId, unblockedTaskId, taskId));
                                log.info("Unblocked task: taskId={}, unblocked by={}", unblockedTaskId, taskId);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse unblocked tasks from Lua result", e);
                    }
                }

                log.info("Task completed: taskId={}", taskId);
            } else {
                throw new RuntimeException("Failed to complete task: " + result);
            }
        } catch (Exception e) {
            log.error("Error completing task: taskId={}", taskId, e);
            throw new RuntimeException("Failed to complete task", e);
        }
    }

    /**
     * 标记任务失败
     */
    public void failTask(String sessionId, int taskId) {
        Task task = taskBoardRepository.findById(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        task.fail();
        taskBoardRepository.save(task);
        log.info("Task failed: sessionId={}, taskId={}", sessionId, taskId);
    }

    /**
     * 更新任务
     */
    public Task updateTask(String sessionId, int taskId, TaskUpdateRequest updates) {
        Task task = taskBoardRepository.findById(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        // 更新允许修改的字段
        if (updates.getSubject() != null) {
            task.setSubject(updates.getSubject());
        }
        if (updates.getDescription() != null) {
            task.setDescription(updates.getDescription());
        }
        if (updates.getStatus() != null) {
            task.setStatus(updates.getStatus());
        }
        if (updates.getOwner() != null) {
            task.setOwner(updates.getOwner());
        }

        task.setUpdatedAt(LocalDateTime.now());
        taskBoardRepository.save(task);
        log.info("Task updated: sessionId={}, taskId={}", sessionId, taskId);
        return task;
    }

    /**
     * 获取任务
     */
    public Task getTask(String sessionId, int taskId) {
        return taskBoardRepository.findById(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    /**
     * 列出会话的所有任务
     */
    public List<Task> listTasks(String sessionId) {
        return taskBoardRepository.findBySessionId(sessionId);
    }

    /**
     * 获取可领取的任务（PENDING 且无阻塞）
     * 按 taskId 排序，低 ID 优先
     */
    public List<Task> getAvailableTasks(String sessionId) {
        return taskBoardRepository.findExecutableTasks(sessionId).stream()
                .filter(task -> task.getStatus() == TaskStatus.PENDING)
                .sorted(Comparator.comparingInt(Task::getTaskId))
                .collect(Collectors.toList());
    }

    /**
     * 添加依赖关系
     */
    public void addDependency(String sessionId, int taskId, int blockedByTaskId) {
        Task task = taskBoardRepository.findById(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        Task blockedByTask = taskBoardRepository.findById(sessionId, blockedByTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Blocked by task not found: " + blockedByTaskId));

        // 添加到 blockedBy
        if (task.getBlockedBy() == null) {
            task.setBlockedBy(new ArrayList<>());
        }
        if (!task.getBlockedBy().contains(blockedByTaskId)) {
            task.getBlockedBy().add(blockedByTaskId);
        }

        // 添加到 blocks
        if (blockedByTask.getBlocks() == null) {
            blockedByTask.setBlocks(new ArrayList<>());
        }
        if (!blockedByTask.getBlocks().contains(taskId)) {
            blockedByTask.getBlocks().add(taskId);
        }

        // 验证无环
        List<Task> allTasks = taskBoardRepository.findBySessionId(task.getSessionId());
        TaskValidator.ValidationResult result = TaskValidator.validate(allTasks);
        if (!result.isValid()) {
            throw new IllegalArgumentException("Adding dependency would create a cycle: " + result.getErrorMessage());
        }

        taskBoardRepository.save(task);
        taskBoardRepository.save(blockedByTask);
        log.info("Added dependency: taskId={} blocked by {}", taskId, blockedByTaskId);
    }

    /**
     * 移除依赖关系
     */
    public void removeDependency(String sessionId, int taskId, int blockedByTaskId) {
        Task task = taskBoardRepository.findById(sessionId, taskId).orElse(null);
        Task blockedByTask = taskBoardRepository.findById(sessionId, blockedByTaskId).orElse(null);

        if (task != null && task.getBlockedBy() != null) {
            task.getBlockedBy().remove(Integer.valueOf(blockedByTaskId));
            taskBoardRepository.save(task);
        }

        if (blockedByTask != null && blockedByTask.getBlocks() != null) {
            blockedByTask.getBlocks().remove(Integer.valueOf(taskId));
            taskBoardRepository.save(blockedByTask);
        }

        log.info("Removed dependency: taskId={} no longer blocked by {}", taskId, blockedByTaskId);
    }

    /**
     * 验证会话的所有任务依赖关系
     */
    public TaskValidator.ValidationResult validateDependencies(String sessionId) {
        List<Task> tasks = taskBoardRepository.findBySessionId(sessionId);
        return TaskValidator.validate(tasks);
    }

    /**
     * 删除任务
     */
    public void deleteTask(String sessionId, int taskId) {
        Task task = taskBoardRepository.findById(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        // 清理依赖关系
        if (task.getBlocks() != null) {
            for (int blockedTaskId : task.getBlocks()) {
                removeDependency(sessionId, blockedTaskId, taskId);
            }
        }
        if (task.getBlockedBy() != null) {
            for (int blockedByTaskId : task.getBlockedBy()) {
                Task blockedByTask = taskBoardRepository.findById(sessionId, blockedByTaskId).orElse(null);
                if (blockedByTask != null && blockedByTask.getBlocks() != null) {
                    blockedByTask.getBlocks().remove(Integer.valueOf(taskId));
                    taskBoardRepository.save(blockedByTask);
                }
            }
        }

        taskBoardRepository.delete(sessionId, taskId);
        log.info("Deleted task: sessionId={}, taskId={}", sessionId, taskId);
    }

    /**
     * 删除会话的所有任务
     */
    public void deleteAllTasks(String sessionId) {
        taskBoardRepository.deleteBySessionId(sessionId);
        log.info("Deleted all tasks for session: {}", sessionId);
    }
}
