package com.agent.domain.taskboard.model;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    /**
     * 待分配
     */
    PENDING,

    /**
     * 已分配给 Worker（Leader 指派后）
     */
    ASSIGNED,

    /**
     * 执行中
     */
    IN_PROGRESS,

    /**
     * 已完成
     */
    COMPLETED,

    /**
     * 失败
     */
    FAILED
}
