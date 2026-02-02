package com.agent.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.infrastructure.persistence.entity.Message;
import com.agent.infrastructure.persistence.mapper.MessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 消息服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String RECENT_CACHE_PREFIX = "session:msg:";
    private static final long RECENT_CACHE_TTL_HOURS = 24;

    /**
     * recent 缓存统一只维护一个 list，避免不同 limit 互相污染。
     * list 内保持时间顺序 [旧 -> 新]。
     */
    private static final int RECENT_CACHE_MAX_SIZE = 10;

    private static String recentListKey(String sessionId) {
        return RECENT_CACHE_PREFIX + sessionId + ":recent";
    }

    /**
     * 保存消息：写库 + 追加缓存（trim 保持最近 N 条）
     */
    @Transactional
    public Message saveMessage(Message message) {
        messageMapper.insert(message);

        String key = recentListKey(message.getSessionId());

        // 追加到 list 尾部（旧->新）
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, json);
            // 只保留最新 N 条（从尾部开始数）
            redisTemplate.opsForList().trim(key, -RECENT_CACHE_MAX_SIZE, -1);
            redisTemplate.expire(key, RECENT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            // 缓存异常不能影响主流程
            log.debug("[MessageService] Failed to append recent cache: sessionId={}", message.getSessionId(), e);
        }

        log.debug("Message saved for session: {}", message.getSessionId());
        return message;
    }

    /**
     * 查询会话的所有消息
     */
    public List<Message> getSessionMessages(String sessionId) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getSessionId, sessionId)
               .orderByAsc(Message::getCreatedAt);
        return messageMapper.selectList(wrapper);
    }

    /**
     * 查询会话的最近N条消息（高频）：Cache-Aside
     */
    public List<Message> getRecentMessages(String sessionId, int limit) {
        int safeLimit = Math.max(1, limit);
        String key = recentListKey(sessionId);

        // 1) Redis list 命中：取末尾 N 条（保持 [旧 -> 新]）
        try {
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > 0) {
                long start = Math.max(0, size - safeLimit);
                List<String> cached = redisTemplate.opsForList().range(key, start, -1);
                if (cached != null && !cached.isEmpty()) {
                    return cached.stream()
                            .map(s -> {
                                try {
                                    return objectMapper.readValue(s, Message.class);
                                } catch (Exception ex) {
                                    return null;
                                }
                            })
                            .filter(m -> m != null)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("[MessageService] Redis read failed, fallback to DB: sessionId={}, limit={}", sessionId, safeLimit, e);
        }

        // 2) DB fallback：倒序查最新 N 条，然后转为 [旧 -> 新]
        List<Message> dbList = loadRecentFromDb(sessionId, safeLimit);

        // 3) 回填 list：缓存通常是过期/不存在，此时直接 rightPushAll + expire 即可
        if (dbList != null && !dbList.isEmpty()) {
            try {
                List<String> payload = dbList.stream()
                        .map(m -> {
                            try {
                                return objectMapper.writeValueAsString(m);
                            } catch (Exception ex) {
                                return null;
                            }
                        })
                        .filter(s -> s != null)
                        .collect(Collectors.toList());

                if (!payload.isEmpty()) {
                    redisTemplate.opsForList().rightPushAll(key, payload);
                    redisTemplate.opsForList().trim(key, -RECENT_CACHE_MAX_SIZE, -1);
                    redisTemplate.expire(key, RECENT_CACHE_TTL_HOURS, TimeUnit.HOURS);
                }
            } catch (Exception e) {
                log.debug("[MessageService] Failed to backfill recent cache: sessionId={}, limit={}", sessionId, safeLimit, e);
            }
        }

        return dbList;
    }

    private List<Message> loadRecentFromDb(String sessionId, int limit) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getSessionId, sessionId)
               .orderByDesc(Message::getCreatedAt)
               .last("LIMIT " + limit);
        List<Message> list = messageMapper.selectList(wrapper);
        // 转成 [旧 -> 新]，更适合 prompt / UI
        Collections.reverse(list);
        return list;
    }

    /**
     * Render recent messages into a compact string for prompts.
     * Each line: [role] content
     */
    public String renderRecent(String sessionId, int limit) {
        List<Message> recent = getRecentMessages(sessionId, limit);
        return recent.stream()
                .map(m -> {
                    String role = m.getRole() == null ? "" : m.getRole().toLowerCase();
                    // Normalize roles: map possible variants to 'human' or 'agent'
                    if (role.contains("human") || role.contains("user")) role = "human";
                    else if (role.contains("agent") || role.contains("assistant")) role = "agent";
                    else if (role.isBlank()) role = "";
                    return "[" + role + "] " + (m.getContent() == null ? "" : m.getContent());
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 查询Agent在会话中的消息
     */
    public List<Message> getAgentMessages(String sessionId, Long agentId) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getSessionId, sessionId)
               .eq(Message::getAgentId, agentId)
               .orderByAsc(Message::getCreatedAt);
        return messageMapper.selectList(wrapper);
    }

    /**
     * 删除会话的所有消息
     */
    @Transactional
    public void deleteSessionMessages(String sessionId) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getSessionId, sessionId);
        messageMapper.delete(wrapper);

        // 删除 list 缓存
        try {
            redisTemplate.delete(recentListKey(sessionId));
        } catch (Exception e) {
            log.debug("[MessageService] Failed to evict recent cache on delete: sessionId={}", sessionId, e);
        }

        log.info("Messages deleted for session: {}", sessionId);
    }

    /**
     * 统计会话消息数
     */
    public long countSessionMessages(String sessionId) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getSessionId, sessionId);
        return messageMapper.selectCount(wrapper);
    }
    
    /**
     * 追加内容（增量更新）
     */
    public void appendContent(Long messageId, String delta) {
        if (messageId == null || delta == null || delta.isEmpty()) {
            return;
        }
        try {
            messageMapper.appendContent(messageId, delta);
        } catch (Exception e) {
            log.error("[MessageService] Failed to append content: messageId={}", messageId, e);
        }
    }
    
    /**
     * 完成消息（更新状态和最终内容）
     */
    @Transactional
    public void completeMessage(Long messageId, String finalContent) {
        if (messageId == null) {
            return;
        }
        try {
            Message msg = new Message();
            msg.setId(messageId);
            msg.setContent(finalContent);
            msg.setStatus("COMPLETED");
            messageMapper.updateById(msg);
            
            // 删除缓存，让下次重新加载
            // 注意：这里可以选择直接更新缓存中的该条消息，但为了简化，我们直接删除缓存
            Message fullMsg = messageMapper.selectById(messageId);
            if (fullMsg != null) {
                String key = recentListKey(fullMsg.getSessionId());
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.error("[MessageService] Failed to complete message: messageId={}", messageId, e);
        }
    }
}
