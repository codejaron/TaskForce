package com.agent.domain.team.service;

import com.agent.domain.team.model.Team;
import com.agent.domain.team.model.TeamMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 团队领域服务
 * 负责团队生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    /**
     * 创建团队
     * @param sessionId 会话 ID
     * @param leadInstanceId Lead 实例 ID
     * @return 团队对象
     */
    public Team createTeam(String sessionId, String leadInstanceId) {
        throw new UnsupportedOperationException("待 Redis 实现后完成");
    }

    /**
     * 添加成员
     * @param teamId 团队 ID
     * @param member 成员对象
     */
    public void addMember(String teamId, TeamMember member) {
        throw new UnsupportedOperationException("待 Redis 实现后完成");
    }

    /**
     * 移除成员
     * @param teamId 团队 ID
     * @param instanceId 实例 ID
     */
    public void removeMember(String teamId, String instanceId) {
        throw new UnsupportedOperationException("待 Redis 实现后完成");
    }

    /**
     * 关闭团队
     * @param teamId 团队 ID
     */
    public void shutdown(String teamId) {
        throw new UnsupportedOperationException("待 Redis 实现后完成");
    }

    /**
     * 获取团队
     * @param teamId 团队 ID
     * @return 团队对象
     */
    public Team getTeam(String teamId) {
        throw new UnsupportedOperationException("待 Redis 实现后完成");
    }

    /**
     * 根据会话 ID 获取团队
     * @param sessionId 会话 ID
     * @return 团队对象
     */
    public Team getTeamBySessionId(String sessionId) {
        throw new UnsupportedOperationException("待 Redis 实现后完成");
    }
}
