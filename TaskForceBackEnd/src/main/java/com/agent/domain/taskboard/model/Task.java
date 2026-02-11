package com.agent.domain.taskboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务聚合根
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    /**
     * 任务 ID（session 内递增序号）
     */
    private int taskId;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 任务主题
     */
    private String subject;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 任务状态
     */
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /**
     * 任务所有者（Agent ID）
     */
    private String owner;

    /**
     * 被阻塞的任务列表（依赖的任务 ID）
     */
    private List<Integer> blockedBy;

    /**
     * 阻塞的任务列表（被依赖的任务 ID）
     */
    private List<Integer> blocks;

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

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    // === 状态转换方法 ===

    /**
     * Leader 指派任务
     */
    public void assign(String ownerId) {
        this.status = TaskStatus.ASSIGNED;
        this.owner = ownerId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 开始执行（从 ASSIGNED → IN_PROGRESS）
     */
    public void start() {
        this.status = TaskStatus.IN_PROGRESS;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 完成任务
     */
    public void complete() {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 任务失败
     */
    public void fail() {
        this.status = TaskStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return this.status == TaskStatus.COMPLETED;
    }

    /**
     * 是否失败
     */
    public boolean isFailed() {
        return this.status == TaskStatus.FAILED;
    }

    /**
     * 是否可以开始执行（没有被阻塞）
     */
    public boolean canStart() {
        return this.blockedBy == null || this.blockedBy.isEmpty();
    }

    /**
     * 重置为待认领状态
     */
    public void resetToPending() {
        this.status = TaskStatus.PENDING;
        this.owner = null;
        this.completedAt = null;
        this.updatedAt = LocalDateTime.now();
    }
}
