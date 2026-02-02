package com.agent.domain.orchestration.repository;

import com.agent.domain.orchestration.model.ExecutionPlan;

import java.util.Optional;

/**
 * 执行计划仓储接口
 */
public interface PlanRepository {

    /**
     * 根据会话 ID 查找计划
     */
    Optional<ExecutionPlan> findBySessionId(String sessionId);

    /**
     * 根据计划 ID 查找计划
     */
    Optional<ExecutionPlan> findByPlanId(String planId);

    /**
     * 保存计划
     */
    ExecutionPlan save(ExecutionPlan plan);

    /**
     * 删除计划
     */
    void deleteBySessionId(String sessionId);

    /**
     * 检查会话是否有计划
     */
    boolean existsBySessionId(String sessionId);
}
