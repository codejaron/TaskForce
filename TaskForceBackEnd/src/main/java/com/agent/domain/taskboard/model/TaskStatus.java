package com.agent.domain.taskboard.model;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    /**
     * 待认领
     */
    PENDING,

    /**
     * 已认领
     */
    CLAIMED,

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
