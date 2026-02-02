package com.agent.domain.plan;

/**
 * 暂停触发源枚举
 */
public enum PauseSource {
    /**
     * Planner阶段澄清（需要重新规划）
     */
    PLANNER,

    /**
     * Worker执行时澄清（需要重新执行当前步骤）
     */
    WORKER,

    /**
     * 用户手动停止
     */
    USER,

    /**
     * 步骤阻塞（触发Replanner）
     */
    BLOCKED
}
