package com.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.entity.Message;
import com.agent.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 消息服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {
    
    private final MessageMapper messageMapper;
    
    /**
     * 保存消息
     */
    @Transactional
    public Message saveMessage(Message message) {
        messageMapper.insert(message);
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
     * 查询会话的最近N条消息
     */
    public List<Message> getRecentMessages(String sessionId, int limit) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getSessionId, sessionId)
               .orderByDesc(Message::getCreatedAt)
               .last("LIMIT " + limit);
        List<Message> messages = messageMapper.selectList(wrapper);
        // 反转顺序，使其按时间正序
        java.util.Collections.reverse(messages);
        return messages;
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
}
