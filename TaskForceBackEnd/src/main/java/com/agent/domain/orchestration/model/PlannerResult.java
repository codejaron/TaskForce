package com.agent.domain.orchestration.model;

/**
 * Planner 返回类型（sealed interface）
 * 支持三种结果：生成计划、需要澄清、无法完成
 */
public sealed interface PlannerResult permits
        PlannerResult.PlanGenerated,
        PlannerResult.NeedClarification,
        PlannerResult.CannotPlan {

    /**
     * 成功生成计划
     */
    record PlanGenerated(ExecutionPlan plan) implements PlannerResult {
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * 需要用户澄清
     */
    record NeedClarification(String question) implements PlannerResult {
        public boolean needsInput() {
            return true;
        }
    }

    /**
     * 无法完成（目标不合理等）
     */
    record CannotPlan(String reason) implements PlannerResult {
        public boolean isFailed() {
            return true;
        }
    }

    /**
     * 是否成功生成计划
     */
    default boolean isSuccess() {
        return this instanceof PlanGenerated;
    }

    /**
     * 是否需要用户输入
     */
    default boolean needsInput() {
        return this instanceof NeedClarification;
    }

    /**
     * 是否失败
     */
    default boolean isFailed() {
        return this instanceof CannotPlan;
    }
}
