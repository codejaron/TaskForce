package com.agent.domain.worker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Worker 实例聚合根
 * 管理 Worker 的完整生命周期
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerInstance {

    /**
     * Worker 实例 ID（内部路由 ID）
     */
    private String instanceId;

    /**
     * Worker 序号（会话内稳定 ID，供 LLM 与工具使用）
     * 仅在当前 session 内唯一，使用简单数字：1,2,3...
     */
    private int workerId;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * Worker 名称
     */
    private String name;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Worker 状态
     */
    @Builder.Default
    private WorkerStatus status = WorkerStatus.IDLE;

    /**
     * 当前任务 ID（0 表示无任务）
     */
    private int currentTaskId;

    /**
     * Leader 指派的任务 ID（0 表示无指派任务）
     * Worker 启动后优先执行此任务
     */
    private int assignedTaskId;

    /**
     * 启动时间
     */
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    /**
     * 更新时间
     */
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    // === 静态工厂方法 ===

    /**
     * 创建一个新的 Worker 实例
     */
    public static WorkerInstance create(String sessionId, String name, String agentId, int workerId, String instanceId) {
        return WorkerInstance.builder()
                .instanceId(instanceId)
                .workerId(workerId)
                .sessionId(sessionId)
                .name(name)
                .agentId(agentId)
                .status(WorkerStatus.IDLE)
                .currentTaskId(0)
                .assignedTaskId(0)
                .build();
    }

    /**
     * 创建带指派任务的 Worker 实例
     */
    public static WorkerInstance createWithTask(
            String sessionId,
            String name,
            String agentId,
            int workerId,
            String instanceId,
            int assignedTaskId) {
        return WorkerInstance.builder()
                .instanceId(instanceId)
                .workerId(workerId)
                .sessionId(sessionId)
                .name(name)
                .agentId(agentId)
                .assignedTaskId(assignedTaskId)
                .status(WorkerStatus.IDLE)
                .currentTaskId(0)
                .build();
    }

    // === 状态机方法 ===

    /**
     * 是否空闲
     */
    public boolean isIdle() {
        return status == WorkerStatus.IDLE;
    }

    /**
     * 是否工作中
     */
    public boolean isWorking() {
        return status == WorkerStatus.WORKING;
    }

    /**
     * 是否等待中
     */
    public boolean isWaiting() {
        return status == WorkerStatus.WAITING;
    }

    /**
     * 是否已关闭
     */
    public boolean isShutdown() {
        return status == WorkerStatus.SHUTDOWN;
    }

    /**
     * 开始工作
     */
    public void startWorking(int taskId) {
        this.status = WorkerStatus.WORKING;
        this.currentTaskId = taskId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 完成工作
     */
    public void completeWork() {
        this.status = WorkerStatus.IDLE;
        this.currentTaskId = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 进入等待状态
     */
    public void startWaiting() {
        this.status = WorkerStatus.WAITING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 从等待状态恢复
     */
    public void resumeFromWaiting() {
        if (currentTaskId != 0) {
            this.status = WorkerStatus.WORKING;
        } else {
            this.status = WorkerStatus.IDLE;
        }
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 关闭 Worker
     */
    public void shutdown() {
        this.status = WorkerStatus.SHUTDOWN;
        this.currentTaskId = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 更新当前任务
     */
    public void updateCurrentTask(int taskId) {
        this.currentTaskId = taskId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 清除当前任务
     */
    public void clearCurrentTask() {
        this.currentTaskId = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 更新指派任务（Leader 追加新任务时使用）
     */
    public void assignTask(int taskId) {
        this.assignedTaskId = taskId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 清除指派任务
     */
    public void clearAssignedTask() {
        this.assignedTaskId = 0;
        this.updatedAt = LocalDateTime.now();
    }
}
