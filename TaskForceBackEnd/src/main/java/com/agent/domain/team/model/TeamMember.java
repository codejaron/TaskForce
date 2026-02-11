package com.agent.domain.team.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 团队成员值对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamMember {

    /**
     * 实例 ID
     */
    private String instanceId;

    /**
     * 成员名称
     */
    private String name;

    /**
     * 角色
     */
    private String role;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * 成员状态
     */
    private String status;

    /**
     * 加入时间
     */
    @Builder.Default
    private LocalDateTime joinedAt = LocalDateTime.now();
}
