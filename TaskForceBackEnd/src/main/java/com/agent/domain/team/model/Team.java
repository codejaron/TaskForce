package com.agent.domain.team.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 团队聚合根
 * 管理团队生命周期和成员
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Team {

    /**
     * 团队 ID
     */
    @Builder.Default
    private String teamId = UUID.randomUUID().toString();

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * Lead 实例 ID
     */
    private String leadInstanceId;

    /**
     * 团队状态
     */
    @Builder.Default
    private TeamStatus status = TeamStatus.ACTIVE;

    /**
     * 团队成员列表
     */
    @Builder.Default
    private List<TeamMember> members = new ArrayList<>();

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
     * 创建新团队
     */
    public static Team create(String sessionId, String leadInstanceId) {
        return Team.builder()
                .sessionId(sessionId)
                .leadInstanceId(leadInstanceId)
                .status(TeamStatus.ACTIVE)
                .build();
    }

    // === 业务方法 ===

    /**
     * 添加成员
     */
    public void addMember(TeamMember member) {
        if (this.members == null) {
            this.members = new ArrayList<>();
        }
        this.members.add(member);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 移除成员
     */
    public void removeMember(String instanceId) {
        if (this.members != null) {
            this.members.removeIf(m -> m.getInstanceId().equals(instanceId));
            this.updatedAt = LocalDateTime.now();
        }
    }

    /**
     * 查找成员
     */
    public TeamMember findMember(String instanceId) {
        if (this.members == null) {
            return null;
        }
        return this.members.stream()
                .filter(m -> m.getInstanceId().equals(instanceId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 开始关闭
     */
    public void startShutdown() {
        this.status = TeamStatus.SHUTTING_DOWN;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记为已关闭
     */
    public void markClosed() {
        this.status = TeamStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 是否活跃
     */
    public boolean isActive() {
        return this.status == TeamStatus.ACTIVE;
    }

    /**
     * 是否正在关闭
     */
    public boolean isShuttingDown() {
        return this.status == TeamStatus.SHUTTING_DOWN;
    }

    /**
     * 是否已关闭
     */
    public boolean isClosed() {
        return this.status == TeamStatus.CLOSED;
    }

    /**
     * 获取成员数量
     */
    public int getMemberCount() {
        return this.members != null ? this.members.size() : 0;
    }
}
