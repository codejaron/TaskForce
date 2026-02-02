package com.agent.domain.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 执行计划聚合根
 * 作为唯一状态源，可推导当前阶段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 计划 ID
     */
    @Builder.Default
    private String planId = UUID.randomUUID().toString();

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 用户目标
     */
    private String goal;

    /**
     * 步骤列表
     */
    @Builder.Default
    private List<PlanStep> steps = new ArrayList<>();

    /**
     * 当前执行到第几步（0-based）
     */
    @Builder.Default
    private int currentStepIndex = 0;

    /**
     * 计划状态
     */
    @Builder.Default
    private PlanStatus status = PlanStatus.PLANNING;

    /**
     * 暂停原因
     */
    private String pauseReason;

    /**
     * 待用户回答的问题
     */
    private String pendingQuestion;

    /**
     * 暂停触发源
     */
    private PauseSource pausedBy;

    /**
     * Worker澄清时记录的步骤索引
     */
    private Integer pausedAtStepIndex;

    /**
     * Worker澄清时记录的Agent ID
     */
    private String pausedAgentId;

    /**
     * 重规划次数
     */
    @Builder.Default
    private int replanCount = 0;

    /**
     * 创建时间
     */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 更新时间
     */
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    // === 静态工厂方法 ===

    /**
     * 创建一个暂停状态的计划（用于等待用户澄清）
     */
    public static ExecutionPlan createPaused(String sessionId, String question) {
        return ExecutionPlan.builder()
                .sessionId(sessionId)
                .status(PlanStatus.PAUSED)
                .pauseReason("waiting_user")
                .pendingQuestion(question)
                .build();
    }

    /**
     * 创建一个正在规划中的计划
     */
    public static ExecutionPlan createPlanning(String sessionId) {
        return ExecutionPlan.builder()
                .sessionId(sessionId)
                .status(PlanStatus.PLANNING)
                .build();
    }

    // === 状态推导方法（替代 WorkflowState） ===

    /**
     * 获取当前阶段
     */
    public String getCurrentPhase() {
        if (status == PlanStatus.PLANNING) return "PLANNING";
        if (status == PlanStatus.PAUSED) return "WAITING_USER";
        if (status == PlanStatus.COMPLETED) return "COMPLETED";
        if (status == PlanStatus.FAILED) return "FAILED";
        if (status == PlanStatus.REPLANNING) return "REPLANNING";
        if (status == PlanStatus.EXECUTING) {
            PlanStep step = getCurrentStep();
            if (step != null && step.getStatus() == StepStatus.IN_PROGRESS) {
                return "EXECUTING_STEP";
            }
        }
        return "READY_FOR_NEXT_STEP";
    }

    /**
     * 获取当前 Agent ID
     */
    public String getCurrentAgentId() {
        PlanStep step = getCurrentStep();
        return step != null ? step.getAssignedAgentId() : null;
    }

    // === 状态机方法 ===

    /**
     * 获取当前步骤
     */
    public PlanStep getCurrentStep() {
        if (steps == null || currentStepIndex >= steps.size()) {
            return null;
        }
        return steps.get(currentStepIndex);
    }

    /**
     * 是否有下一步
     */
    public boolean hasNextStep() {
        return steps != null && currentStepIndex < steps.size() - 1;
    }

    /**
     * 推进到下一步
     */
    public void advanceToNextStep() {
        if (hasNextStep()) {
            currentStepIndex++;
            updatedAt = LocalDateTime.now();
        }
    }

    /**
     * 是否完成
     */
    public boolean isComplete() {
        return currentStepIndex >= steps.size() || status == PlanStatus.COMPLETED;
    }

    /**
     * 获取已完成步骤数
     */
    public int getCompletedStepCount() {
        if (steps == null) return 0;
        return (int) steps.stream().filter(PlanStep::isDone).count();
    }

    // === 暂停/恢复 ===

    /**
     * Planner阶段暂停等待用户输入（需要完全重新规划）
     */
    public void pauseForPlannerClarification(String question) {
        this.status = PlanStatus.PAUSED;
        this.pauseReason = "waiting_user";
        this.pausedBy = PauseSource.PLANNER;
        this.pendingQuestion = question;
        this.pausedAtStepIndex = null;
        this.pausedAgentId = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Worker执行时暂停等待用户输入（需要重新执行当前步骤）
     */
    public void pauseForWorkerClarification(String question, int stepIndex, String agentId) {
        this.status = PlanStatus.PAUSED;
        this.pauseReason = "waiting_user";
        this.pausedBy = PauseSource.WORKER;
        this.pendingQuestion = question;
        this.pausedAtStepIndex = stepIndex;
        this.pausedAgentId = agentId;
        this.updatedAt = LocalDateTime.now();
        // 注意：步骤状态保持IN_PROGRESS，不改变
    }

    /**
     * 用户手动停止
     */
    public void pauseForUserStop(String reason) {
        this.status = PlanStatus.PAUSED;
        this.pauseReason = "user_stopped";
        this.pausedBy = PauseSource.USER;
        this.pendingQuestion = reason;
        this.pausedAtStepIndex = this.currentStepIndex;
        this.pausedAgentId = getCurrentAgentId();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 暂停等待用户输入（已废弃，保留用于兼容）
     * @deprecated 使用 pauseForPlannerClarification 或 pauseForWorkerClarification
     */
    @Deprecated
    public void pauseForUserInput(String question) {
        this.status = PlanStatus.PAUSED;
        this.pauseReason = "waiting_user";
        this.pendingQuestion = question;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 暂停因为阻塞
     */
    public void pauseForBlocked(String reason) {
        this.status = PlanStatus.PAUSED;
        this.pauseReason = "blocked";
        this.pausedBy = PauseSource.BLOCKED;
        this.pendingQuestion = reason;
        this.pausedAtStepIndex = this.currentStepIndex;
        this.pausedAgentId = getCurrentAgentId();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 恢复执行（清除暂停标记）
     */
    public void resume() {
        this.status = PlanStatus.EXECUTING;
        this.pauseReason = null;
        this.pendingQuestion = null;
        // 注意：不清除 pausedBy/pausedAtStepIndex/pausedAgentId，
        // 这些信息在processAsync中用于判断恢复策略
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 清除暂停上下文（完成恢复后调用）
     */
    public void clearPauseContext() {
        this.pausedBy = null;
        this.pausedAtStepIndex = null;
        this.pausedAgentId = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 是否需要完全重新规划（Planner澄清）
     */
    public boolean needsFullReplanning() {
        return pausedBy == PauseSource.PLANNER;
    }

    /**
     * 是否需要重新执行被打断的步骤（Worker澄清）
     */
    public boolean needsRestepExecution() {
        return pausedBy == PauseSource.WORKER;
    }

    /**
     * 获取被打断的步骤（Worker澄清恢复时使用）
     */
    public PlanStep getPausedStep() {
        if (pausedAtStepIndex != null && steps != null && pausedAtStepIndex < steps.size()) {
            return steps.get(pausedAtStepIndex);
        }
        return null;
    }

    /**
     * 重置到被打断的步骤（Worker澄清恢复时调用）
     */
    public void resetToPausedStep() {
        if (pausedAtStepIndex != null) {
            this.currentStepIndex = pausedAtStepIndex;
            // 步骤状态保持IN_PROGRESS，不改变
        }
    }

    /**
     * 开始执行
     */
    public void startExecuting() {
        this.status = PlanStatus.EXECUTING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 开始重规划
     */
    public void startReplanning() {
        this.status = PlanStatus.REPLANNING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 增加重规划计数
     */
    public void incrementReplanCount() {
        this.replanCount++;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记完成
     */
    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记失败
     */
    public void markFailed(String reason) {
        this.status = PlanStatus.FAILED;
        this.pauseReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 转换为 JSON
     */
    public String toJson() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * 是否可以重规划
     */
    public boolean canReplan(int maxReplanCount) {
        return replanCount < maxReplanCount;
    }

    /**
     * 更新步骤列表（用于重规划）
     */
    public void updateSteps(List<PlanStep> newSteps) {
        this.steps = newSteps;
        this.currentStepIndex = 0;
        this.status = PlanStatus.EXECUTING;
        this.replanCount++;
        this.updatedAt = LocalDateTime.now();
    }
}
