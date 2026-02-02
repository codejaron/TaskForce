package com.agent.domain.plan;

/**
 * 步骤状态枚举
 */
public enum StepStatus {
    /**
     * 待执行
     */
    PENDING,

    /**
     * 执行中
     */
    IN_PROGRESS,

    /**
     * 完成
     */
    DONE,

    /**
     * 阻塞
     */
    BLOCKED
}
