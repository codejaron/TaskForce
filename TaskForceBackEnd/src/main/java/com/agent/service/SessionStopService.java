package com.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 会话停止控制服务（基于 Redis 的分布式实现）
 * 用于管理用户主动停止会话流的标志，支持多节点部署
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionStopService {

    private final StringRedisTemplate redisTemplate;
    private final SessionExecutionTracker executionTracker;

    private static final String STOP_KEY_PREFIX = "session:stop:";
    private static final long STOP_FLAG_TTL_SECONDS = 300; // 5 分钟过期

    /**
     * 标记会话需要停止（旧方法，保留向后兼容）
     */
    public void markStop(String sessionId) {
        markStopAndCancel(sessionId);
    }

    /**
     * 标记会话需要停止并主动取消所有任务
     */
    public void markStopAndCancel(String sessionId) {
        String key = STOP_KEY_PREFIX + sessionId;
        // 1. 设置停止标志
        redisTemplate.opsForValue().set(key, "1", STOP_FLAG_TTL_SECONDS, TimeUnit.SECONDS);
        log.info("[StopService] Marked stop: sessionId={}", sessionId);

        // 2. 主动取消所有任务
        executionTracker.cancelAll(sessionId);
        log.info("[StopService] Cancelled all tasks: sessionId={}", sessionId);
    }

    /**
     * 检查会话是否需要停止
     */
    public boolean shouldStop(String sessionId) {
        String key = STOP_KEY_PREFIX + sessionId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 清除会话停止标志（继续执行）
     */
    public void clearStop(String sessionId) {
        String key = STOP_KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
        log.debug("[StopService] Cleared stop: sessionId={}", sessionId);
    }
}

