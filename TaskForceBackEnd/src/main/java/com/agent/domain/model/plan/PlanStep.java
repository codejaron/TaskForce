package com.agent.domain.model.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 计划步骤实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {

    /**
     * 步骤 ID
     */
    @Builder.Default
    private String stepId = UUID.randomUUID().toString();

    /**
     * 步骤序号（从 1 开始）
     */
    private int stepIndex;

    /**
     * 步骤描述
     */
    private String description;

    /**
     * 分配的 Agent ID
     */
    private String assignedAgentId;

    /**
     * 分配的 Agent 名称（用于展示）
     */
    private String assignedAgentName;

    /**
     * 所需能力
     */
    private String requiredCapability;

    /**
     * 详细执行指令
     */
    private String instruction;

    /**
     * 期望输出格式
     */
    private String expectedOutput;

    /**
     * 步骤状态
     */
    @Builder.Default
    private StepStatus status = StepStatus.PENDING;

    /**
     * 执行结果
     */
    private String result;

    /**
     * 阻塞原因（当 status == BLOCKED 时有值）
     */
    private String blockedReason;

    // === 状态转换方法 ===

    /**
     * 开始执行
     */
    public void start() {
        this.status = StepStatus.IN_PROGRESS;
    }

    /**
     * 完成步骤
     */
    public void complete(String result) {
        this.status = StepStatus.DONE;
        this.result = result;
    }

    /**
     * 标记为阻塞
     */
    public void block(String reason) {
        this.status = StepStatus.BLOCKED;
        this.blockedReason = reason;
    }

    /**
     * 是否已完成
     */
    public boolean isDone() {
        return this.status == StepStatus.DONE;
    }

    /**
     * 是否阻塞
     */
    public boolean isBlocked() {
        return this.status == StepStatus.BLOCKED;
    }

    /**
     * 重置为待执行状态（用于恢复被中断的步骤）
     */
    public void resetToPending() {
        this.status = StepStatus.PENDING;
        this.result = null;
        this.blockedReason = null;
    }
}
