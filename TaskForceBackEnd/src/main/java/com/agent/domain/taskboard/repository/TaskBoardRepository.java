package com.agent.domain.taskboard.repository;

import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * 任务板仓储接口
 */
public interface TaskBoardRepository {

    /**
     * 保存任务
     */
    void save(Task task);

    /**
     * 批量保存任务
     */
    void saveAll(List<Task> tasks);

    /**
     * 根据任务 ID 查询任务
     */
    Optional<Task> findById(String sessionId, int taskId);

    /**
     * 根据会话 ID 查询所有任务
     */
    List<Task> findBySessionId(String sessionId);

    /**
     * 根据会话 ID 和状态查询任务
     */
    List<Task> findBySessionIdAndStatus(String sessionId, TaskStatus status);

    /**
     * 根据会话 ID 和所有者查询任务
     */
    List<Task> findBySessionIdAndOwner(String sessionId, String owner);

    /**
     * 查询会话中可执行的任务（PENDING 或 ASSIGNED 且没有被阻塞）
     */
    List<Task> findExecutableTasks(String sessionId);

    /**
     * 删除任务
     */
    void delete(String sessionId, int taskId);

    /**
     * 删除会话的所有任务
     */
    void deleteBySessionId(String sessionId);

    /**
     * 检查任务是否存在
     */
    boolean exists(String sessionId, int taskId);
}
