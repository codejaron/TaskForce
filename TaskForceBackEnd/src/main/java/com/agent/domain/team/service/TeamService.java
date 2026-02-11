package com.agent.domain.team.service;

import com.agent.domain.team.model.Team;
import com.agent.domain.team.model.TeamMember;
import com.agent.domain.team.repository.TeamRepository;
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

    private final TeamRepository teamRepository;

    /**
     * 创建团队
     * @param sessionId 会话 ID
     * @param leadInstanceId Lead 实例 ID
     * @return 团队对象
     */
    public Team createTeam(String sessionId, String leadInstanceId) {
        // 检查会话是否已有团队
        if (teamRepository.existsBySessionId(sessionId)) {
            throw new IllegalStateException("Session already has a team: " + sessionId);
        }

        Team team = Team.create(sessionId, leadInstanceId);
        teamRepository.save(team);

        log.info("Created team: teamId={}, sessionId={}, leadInstanceId={}",
                team.getTeamId(), sessionId, leadInstanceId);
        return team;
    }

    /**
     * 添加成员
     * @param teamId 团队 ID
     * @param member 成员对象
     */
    public void addMember(String teamId, TeamMember member) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        team.addMember(member);
        teamRepository.save(team);

        log.info("Added member to team: teamId={}, instanceId={}, name={}",
                teamId, member.getInstanceId(), member.getName());
    }

    /**
     * 移除成员
     * @param teamId 团队 ID
     * @param instanceId 实例 ID
     */
    public void removeMember(String teamId, String instanceId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        team.removeMember(instanceId);
        teamRepository.save(team);

        log.info("Removed member from team: teamId={}, instanceId={}", teamId, instanceId);
    }

    /**
     * 关闭团队
     * @param teamId 团队 ID
     */
    public void shutdown(String teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        team.markClosed();
        teamRepository.save(team);

        log.info("Shutdown team: teamId={}", teamId);
    }

    /**
     * 获取团队
     * @param teamId 团队 ID
     * @return 团队对象
     */
    public Team getTeam(String teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    /**
     * 根据会话 ID 获取团队
     * @param sessionId 会话 ID
     * @return 团队对象
     */
    public Team getTeamBySessionId(String sessionId) {
        return teamRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found for session: " + sessionId));
    }
}
