package com.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.entity.Agent;
import com.agent.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统Agent管理服务
 * 统一管理系统级别的特殊Agent，通过role_type区分
 *
 * 注意：现在只保留通用查询方法，不再为特定系统Agent提供专用getter
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemAgentService {

    private final AgentMapper agentMapper;

    /**
     * 根据role_type查询系统Agent
     * @param roleType 角色类型（PLANNER, MODERATOR等）
     * @return Agent实体，不存在返回null
     */
    public Agent getSystemAgentByRole(String roleType) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Agent::getRoleType, roleType);
        wrapper.last("LIMIT 1"); // 只取第一个匹配的
        Agent agent = agentMapper.selectOne(wrapper);
        if (agent == null) {
            log.warn("System agent not found for role_type: {}", roleType);
        }
        return agent;
    }

    /**
     * 更新系统Agent的provider_id和model
     * @param roleType 角色类型
     * @param providerId LLM提供商ID
     * @param model 模型标识
     */
    @Transactional
    public void updateSystemAgent(String roleType, Long providerId, String model) {
        Agent agent = getSystemAgentByRole(roleType);
        if (agent == null) {
            throw new IllegalArgumentException("System agent not found: " + roleType);
        }
        agent.setProviderId(providerId);
        agent.setModel(model);
        agentMapper.updateById(agent);
        log.info("System agent updated - role_type: {}, providerId: {}, model: {}", roleType, providerId, model);
    }

    /**
     * 验证系统Agent是否存在
     * @param roleType 角色类型
     * @return 存在返回true，否则返回false
     */
    public boolean existsSystemAgent(String roleType) {
        return getSystemAgentByRole(roleType) != null;
    }
}
