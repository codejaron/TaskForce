package com.agent.infrastructure.persistence.redis;

import com.agent.domain.team.model.Team;
import com.agent.domain.team.repository.TeamRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

/**
 * 团队 Redis 仓储实现
 * 键结构：team:{sessionId}
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisTeamRepository implements TeamRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "team:";
    private static final String SESSION_INDEX_PREFIX = "team:session:";

    @Override
    public Team save(Team team) {
        try {
            String key = buildKey(team.getTeamId());
            String sessionIndexKey = buildSessionIndexKey(team.getSessionId());
            String json = objectMapper.writeValueAsString(team);

            // 保存团队数据
            redisTemplate.opsForValue().set(key, json);

            // 保存 sessionId -> teamId 索引
            redisTemplate.opsForValue().set(sessionIndexKey, team.getTeamId());

            log.debug("Saved team: teamId={}, sessionId={}", team.getTeamId(), team.getSessionId());
            return team;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Team: {}", team.getTeamId(), e);
            throw new RuntimeException("Failed to save Team", e);
        }
    }

    @Override
    public Optional<Team> findById(String teamId) {
        try {
            String key = buildKey(teamId);
            String json = redisTemplate.opsForValue().get(key);

            if (json == null) {
                return Optional.empty();
            }

            Team team = objectMapper.readValue(json, Team.class);
            return Optional.of(team);
        } catch (Exception e) {
            log.error("Failed to find Team by id: {}", teamId, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Team> findBySessionId(String sessionId) {
        try {
            // 先从索引获取 teamId
            String sessionIndexKey = buildSessionIndexKey(sessionId);
            String teamId = redisTemplate.opsForValue().get(sessionIndexKey);

            if (teamId == null) {
                return Optional.empty();
            }

            // 再根据 teamId 获取团队数据
            return findById(teamId);
        } catch (Exception e) {
            log.error("Failed to find Team by sessionId: {}", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String teamId) {
        try {
            // 先获取团队以获得 sessionId
            Optional<Team> teamOpt = findById(teamId);
            if (teamOpt.isEmpty()) {
                return;
            }

            Team team = teamOpt.get();
            String key = buildKey(teamId);
            String sessionIndexKey = buildSessionIndexKey(team.getSessionId());

            // 删除团队数据和索引
            redisTemplate.delete(key);
            redisTemplate.delete(sessionIndexKey);

            log.debug("Deleted team: teamId={}", teamId);
        } catch (Exception e) {
            log.error("Failed to delete Team: {}", teamId, e);
            throw new RuntimeException("Failed to delete Team", e);
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        try {
            // 先从索引获取 teamId
            String sessionIndexKey = buildSessionIndexKey(sessionId);
            String teamId = redisTemplate.opsForValue().get(sessionIndexKey);

            if (teamId != null) {
                String key = buildKey(teamId);
                redisTemplate.delete(key);
                redisTemplate.delete(sessionIndexKey);
                log.debug("Deleted team by sessionId: {}", sessionId);
            }
        } catch (Exception e) {
            log.error("Failed to delete Team by sessionId: {}", sessionId, e);
            throw new RuntimeException("Failed to delete Team by sessionId", e);
        }
    }

    @Override
    public boolean existsById(String teamId) {
        try {
            String key = buildKey(teamId);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("Failed to check existence of Team: {}", teamId, e);
            return false;
        }
    }

    @Override
    public boolean existsBySessionId(String sessionId) {
        try {
            String sessionIndexKey = buildSessionIndexKey(sessionId);
            return Boolean.TRUE.equals(redisTemplate.hasKey(sessionIndexKey));
        } catch (Exception e) {
            log.error("Failed to check existence of Team by sessionId: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 构建 Redis 键
     */
    private String buildKey(String teamId) {
        return KEY_PREFIX + teamId;
    }

    /**
     * 构建会话索引键
     */
    private String buildSessionIndexKey(String sessionId) {
        return SESSION_INDEX_PREFIX + sessionId;
    }
}
