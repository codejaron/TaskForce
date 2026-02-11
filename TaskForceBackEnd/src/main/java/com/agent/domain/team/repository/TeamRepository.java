package com.agent.domain.team.repository;

import com.agent.domain.team.model.Team;

import java.util.Optional;

/**
 * 团队仓储接口
 */
public interface TeamRepository {

    /**
     * 保存团队
     */
    Team save(Team team);

    /**
     * 根据团队 ID 查找
     */
    Optional<Team> findById(String teamId);

    /**
     * 根据会话 ID 查找
     */
    Optional<Team> findBySessionId(String sessionId);

    /**
     * 删除团队
     */
    void delete(String teamId);

    /**
     * 根据会话 ID 删除
     */
    void deleteBySessionId(String sessionId);

    /**
     * 检查团队是否存在
     */
    boolean existsById(String teamId);

    /**
     * 检查会话是否有团队
     */
    boolean existsBySessionId(String sessionId);
}
