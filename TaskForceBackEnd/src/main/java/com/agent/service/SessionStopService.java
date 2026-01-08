package com.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话停止控制服务
 * 用于管理用户主动停止会话流的标志
 */
@Slf4j
@Service
public class SessionStopService {

    // 存储会话停止标志：sessionId -> stopFlag
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();

    /**
     * 标记会话需要停止
     */
    public void markStop(String sessionId) {
        stopFlags.put(sessionId, true);
        log.info("[SessionStop] Session marked to stop: {}", sessionId);
    }

    /**
     * 检查会话是否需要停止
     */
    public boolean shouldStop(String sessionId) {
        return stopFlags.getOrDefault(sessionId, false);
    }

    /**
     * 清除会话停止标志（继续执行）
     */
    public void clearStop(String sessionId) {
        stopFlags.remove(sessionId);
        log.info("[SessionStop] Session stop flag cleared: {}", sessionId);
    }

    /**
     * 重置所有停止标志
     */
    public void clearAll() {
        stopFlags.clear();
        log.info("[SessionStop] All stop flags cleared");
    }
}

