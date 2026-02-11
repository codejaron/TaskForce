package com.agent.domain.worker.repository;

import com.agent.domain.worker.model.WorkerInstance;

import java.util.List;
import java.util.Optional;

/**
 * Worker 实例仓储接口
 */
public interface WorkerInstanceRepository {

    /**
     * 保存 Worker 实例
     */
    WorkerInstance save(WorkerInstance instance);

    /**
     * 根据实例 ID 查找 Worker
     */
    Optional<WorkerInstance> findById(String instanceId);

    /**
     * 根据会话 ID 查找所有 Worker
     */
    List<WorkerInstance> findBySessionId(String sessionId);

    /**
     * 删除 Worker 实例
     */
    void delete(String instanceId);

    /**
     * 根据会话 ID 删除所有 Worker
     */
    void deleteBySessionId(String sessionId);

    /**
     * 检查实例是否存在
     */
    boolean existsById(String instanceId);
}
