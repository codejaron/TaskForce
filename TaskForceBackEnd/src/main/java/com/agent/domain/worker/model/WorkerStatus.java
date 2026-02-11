package com.agent.domain.worker.model;

/**
 * Worker 状态枚举
 */
public enum WorkerStatus {
    /**
     * 空闲状态
     */
    IDLE,

    /**
     * 工作中
     */
    WORKING,

    /**
     * 等待中
     */
    WAITING,

    /**
     * 已关闭
     */
    SHUTDOWN
}
