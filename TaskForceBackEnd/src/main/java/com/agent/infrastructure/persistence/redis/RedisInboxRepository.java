package com.agent.infrastructure.persistence.redis;

import com.agent.domain.team.model.TeamMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 收件箱 Redis 仓储实现
 * 键结构：inbox:{sessionId}:{instanceId}，使用 List 结构
 * LPUSH 写入消息，RPOP 读取消息
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisInboxRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "inbox:";

    /**
     * 发送消息
     */
    public void send(String sessionId, String instanceId, TeamMessage message) {
        try {
            String key = buildKey(sessionId, instanceId);
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().leftPush(key, json);
            log.debug("Sent message to inbox: {}", key);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize TeamMessage", e);
            throw new RuntimeException("Failed to send message", e);
        }
    }

    /**
     * 读取收件箱（读取所有消息并清空）
     */
    public List<TeamMessage> readInbox(String sessionId, String instanceId) {
        try {
            String key = buildKey(sessionId, instanceId);
            List<TeamMessage> messages = new ArrayList<>();

            // 循环 RPOP 直到队列为空
            String json;
            while ((json = redisTemplate.opsForList().rightPop(key)) != null) {
                TeamMessage message = objectMapper.readValue(json, TeamMessage.class);
                messages.add(message);
            }

            log.debug("Read {} messages from inbox: {}", messages.size(), key);
            return messages;
        } catch (Exception e) {
            log.error("Failed to read inbox: {}:{}", sessionId, instanceId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 检查是否有新消息
     */
    public boolean hasNewMessages(String sessionId, String instanceId) {
        try {
            String key = buildKey(sessionId, instanceId);
            Long size = redisTemplate.opsForList().size(key);
            return size != null && size > 0;
        } catch (Exception e) {
            log.error("Failed to check new messages: {}:{}", sessionId, instanceId, e);
            return false;
        }
    }

    /**
     * 清空收件箱
     */
    public void clearInbox(String sessionId, String instanceId) {
        try {
            String key = buildKey(sessionId, instanceId);
            redisTemplate.delete(key);
            log.debug("Cleared inbox: {}", key);
        } catch (Exception e) {
            log.error("Failed to clear inbox: {}:{}", sessionId, instanceId, e);
            throw new RuntimeException("Failed to clear inbox", e);
        }
    }

    /**
     * 构建 Redis 键
     */
    private String buildKey(String sessionId, String instanceId) {
        return KEY_PREFIX + sessionId + ":" + instanceId;
    }
}
