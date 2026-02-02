package com.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.api.request.SessionCreateRequest;
import com.agent.infrastructure.persistence.entity.Session;
import com.agent.infrastructure.persistence.entity.SessionAgent;
import com.agent.infrastructure.persistence.mapper.SessionAgentMapper;
import com.agent.infrastructure.persistence.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 会话服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {
    
    private final SessionMapper sessionMapper;
    private final SessionAgentMapper sessionAgentMapper;
    
    /**
     * 创建会话
     */
    @Transactional
    public Session createSession(SessionCreateRequest request) {
        // 1. 创建会话（全局共享，无需userId）
        String sessionId = UUID.randomUUID().toString();
        Session session = Session.builder()
            .id(sessionId)
            .name(request.getName())
            .type(request.getType() != null ? request.getType() : "SINGLE")
            .status("PENDING")
            .config(request.getConfig())
            .maxRounds(request.getMaxRounds() != null ? request.getMaxRounds() : 10)
            .currentRound(0)
            .build();

        sessionMapper.insert(session);

        // 2. 添加智能体
        if (request.getAgentIds() != null && !request.getAgentIds().isEmpty()) {
            int order = 0;
            for (Long agentId : request.getAgentIds()) {
                SessionAgent sessionAgent = SessionAgent.builder()
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .isAdmin(order == 0) // 第一个设为管理员
                    .joinOrder(order++)
                    .build();
                sessionAgentMapper.insert(sessionAgent);
            }
        }

        log.info("Session created: {}", sessionId);
        return session;
    }
    
    /**
     * 更新会话状态
     */
    @Transactional
    public Session updateSessionStatus(String sessionId, String status) {
        Session session = getSessionById(sessionId);

        session.setStatus(status);
        sessionMapper.updateById(session);

        log.info("Session status updated: {} -> {}", sessionId, status);
        return session;
    }
    
    /**
     * 查询所有会话
     */
    public List<Session> getAllSessions() {
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Session::getCreatedAt);
        return sessionMapper.selectList(wrapper);
    }
    
    /**
     * 根据类型查询会话
     */
    public List<Session> getSessionsByType(String type) {
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Session::getType, type)
               .orderByDesc(Session::getCreatedAt);
        return sessionMapper.selectList(wrapper);
    }
    
    /**
     * 根据状态查询会话
     */
    public List<Session> getSessionsByStatus(String status) {
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Session::getStatus, status)
               .orderByDesc(Session::getCreatedAt);
        return sessionMapper.selectList(wrapper);
    }
    
    /**
     * 根据ID查询会话
     */
    public Session getSessionById(String sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }
        return session;
    }
    
    /**
     * 查询会话中的智能体
     */
    public List<SessionAgent> getSessionAgents(String sessionId) {
        LambdaQueryWrapper<SessionAgent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionAgent::getSessionId, sessionId)
               .orderByAsc(SessionAgent::getJoinOrder);
        return sessionAgentMapper.selectList(wrapper);
    }
    
    /**
     * 添加智能体到会话
     */
    @Transactional
    public void addAgentToSession(String sessionId, Long agentId) {
        Session session = getSessionById(sessionId);

        // 获取当前最大顺序
        LambdaQueryWrapper<SessionAgent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionAgent::getSessionId, sessionId)
               .orderByDesc(SessionAgent::getJoinOrder)
               .last("LIMIT 1");
        SessionAgent lastAgent = sessionAgentMapper.selectOne(wrapper);
        int nextOrder = lastAgent != null ? lastAgent.getJoinOrder() + 1 : 0;

        // 添加智能体
        SessionAgent sessionAgent = SessionAgent.builder()
            .sessionId(sessionId)
            .agentId(agentId)
            .isAdmin(false)
            .joinOrder(nextOrder)
            .build();

        sessionAgentMapper.insert(sessionAgent);
        log.info("Agent {} added to session {}", agentId, sessionId);
    }
    
    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(String sessionId) {
        Session session = getSessionById(sessionId);

        // 删除会话（级联删除关联的智能体和消息由数据库外键处理）
        sessionMapper.deleteById(sessionId);

        // 删除会话智能体关联
        LambdaQueryWrapper<SessionAgent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionAgent::getSessionId, sessionId);
        sessionAgentMapper.delete(wrapper);

        log.info("Session deleted: {}", sessionId);
    }
}
