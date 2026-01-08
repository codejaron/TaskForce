package com.agent.domain.model.plan;

/**
 * 计划状态枚举
 */
public enum PlanStatus {
    /**
     * 正在生成计划
     */
    PLANNING,

    /**
     * 正在执行步骤
     */
    EXECUTING,

    /**
     * 正在重规划
     */
    REPLANNING,

    /**
     * 暂停（等待用户或阻塞）
     */
    PAUSED,

    /**
     * 全部完成
     */
    COMPLETED,

    /**
     * 失败
     */
    FAILED
}
