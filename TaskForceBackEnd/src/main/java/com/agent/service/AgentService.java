package com.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.dto.AgentRequest;
import com.agent.entity.Agent;
import com.agent.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 智能体服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentToolService agentToolService;
    
    /**
     * 创建智能体
     */
    @Transactional
    public Agent createAgent(AgentRequest request) {
        Agent agent = Agent.builder()
            .providerId(request.getProviderId())
            .name(request.getName())
            .model(request.getEffectiveModel())
            .systemPrompt(request.getSystemPrompt())
            .temperature(request.getTemperature() != null ? request.getTemperature() : new BigDecimal("0.70"))
            .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 4096)
            .description(request.getDescription())
            .roleType(request.getRoleType() != null ? request.getRoleType() : "WORKER")
            .build();

        agentMapper.insert(agent);
        log.info("Agent created: {}", agent.getName());

        // 保存MCP工具配置
        if (request.getSelectedMcpTools() != null && !request.getSelectedMcpTools().isEmpty()) {
            log.info("Configuring {} tools for agent {}: {}",
                request.getSelectedMcpTools().size(), agent.getId(), request.getSelectedMcpTools());
            agentToolService.setAgentTools(agent.getId(), request.getSelectedMcpTools());
        } else {
            log.info("No tools selected for agent {}", agent.getId());
        }

        return agent;
    }
    
    /**
     * 更新智能体
     */
    @Transactional
    public Agent updateAgent(Long agentId, AgentRequest request) {
        // 1. 获取Agent
        Agent agent = getAgentById(agentId);

        // 2. 更新字段
        if (request.getName() != null) {
            agent.setName(request.getName());
        }
        if (request.getProviderId() != null) {
            agent.setProviderId(request.getProviderId());
        }
        String effectiveModel = request.getEffectiveModel();
        if (effectiveModel != null) {
            agent.setModel(effectiveModel);
        }
        if (request.getSystemPrompt() != null) {
            agent.setSystemPrompt(request.getSystemPrompt());
        }
        if (request.getTemperature() != null) {
            agent.setTemperature(request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            agent.setMaxTokens(request.getMaxTokens());
        }
        if (request.getDescription() != null) {
            agent.setDescription(request.getDescription());
        }
        if (request.getRoleType() != null) {
            agent.setRoleType(request.getRoleType());
        }

        agentMapper.updateById(agent);
        log.info("Agent updated: {}", agent.getName());

        // 更新MCP工具配置
        if (request.getSelectedMcpTools() != null) {
            log.info("Updating tools for agent {}: {}",
                agent.getId(), request.getSelectedMcpTools());
            agentToolService.setAgentTools(agent.getId(), request.getSelectedMcpTools());
        }

        return agent;
    }
    
    /**
     * 删除智能体
     */
    @Transactional
    public void deleteAgent(Long agentId) {
        // 1. 获取Agent
        Agent agent = getAgentById(agentId);

        // 2. 删除
        agentMapper.deleteById(agentId);
        log.info("Agent deleted: {}", agent.getName());
    }
    
    /**
     * 查询所有智能体（排除系统内置Agent）
     */
    public List<Agent> getAllAgents() {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Agent::getCreatedAt);

        // 排除系统内置的5个Agent
        wrapper.notIn(Agent::getRoleType,
            "SYSTEM_EMBEDDING",
            "SYSTEM_SUMMARIZER",
            "SYSTEM_INTENT_TRANSLATOR",
            "SYSTEM_OUTPUT_SUMMARIZER",
            "MODERATOR"
        );

        return agentMapper.selectList(wrapper);
    }

    /**
     * 查询所有智能体（包含系统内置Agent）
     * 用于系统配置页初始化/展示。
     */
    public List<Agent> getAllAgentsIncludeSystem() {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Agent::getCreatedAt);
        return agentMapper.selectList(wrapper);
    }

    /**
     * 根据ID查询智能体
     */
    public Agent getAgentById(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new RuntimeException("智能体不存在");
        }
        return agent;
    }
}
