package com.agent.application.service;

import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.domain.agent.AgentProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 智能体配置管理服务
 * 提供智能体的 CRUD 操作
 *
 * 注意：本服务仅用于将数据库Agent转换为AgentProfile模型
 * 不再维护内存缓存，避免数据不一致问题
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentProfileService {

    private final AgentService agentService;
    private final ChannelModelService channelModelService;

    /**
     * 创建智能体（已废弃：请直接使用 AgentService）
     * @deprecated 请使用 AgentService.createAgent()
     */
    @Deprecated
    public AgentProfile create(AgentProfile profile) {
        log.warn("AgentProfileService.create() is deprecated, use AgentService instead");
        throw new UnsupportedOperationException("Please use AgentService.createAgent() instead");
    }

    /**
     * 更新智能体（已废弃：请直接使用 AgentService）
     * @deprecated 请使用 AgentService.updateAgent()
     */
    @Deprecated
    public AgentProfile update(String id, AgentProfile profile) {
        log.warn("AgentProfileService.update() is deprecated, use AgentService instead");
        throw new UnsupportedOperationException("Please use AgentService.updateAgent() instead");
    }

    /**
     * 删除智能体（已废弃：请直接使用 AgentService）
     * @deprecated 请使用 AgentService.deleteAgent()
     */
    @Deprecated
    public void delete(String id) {
        log.warn("AgentProfileService.delete() is deprecated, use AgentService instead");
        throw new UnsupportedOperationException("Please use AgentService.deleteAgent() instead");
    }

    /**
     * 根据 ID 查找（从数据库加载）
     */
    public Optional<AgentProfile> findById(String id) {
        try {
            // 从数据库查找
            Long agentId = Long.parseLong(id);
            Agent agent = agentService.getAgentById(agentId);

            if (agent == null) {
                return Optional.empty();
            }

            // 转换为 AgentProfile
            AgentProfile profile = convertToAgentProfile(agent);
            return Optional.of(profile);

        } catch (NumberFormatException e) {
            log.warn("Invalid agent ID format: {}", id);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to load agent: {}", id, e);
            return Optional.empty();
        }
    }

    /**
     * 获取所有智能体（从数据库加载）
     */
    public List<AgentProfile> listAll() {
        try {
            // 从数据库获取所有 Agent
            List<Agent> agents = agentService.getAllAgents();

            // 转换为 AgentProfile
            return agents.stream()
                    .map(this::convertToAgentProfile)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to load agents from database", e);
            throw new RuntimeException("Failed to load agents", e);
        }
    }

    /**
     * 检查是否存在
     */
    public boolean exists(String id) {
        return findById(id).isPresent();
    }
    
    /**
     * 将数据库 Agent 转换为 AgentProfile
     */
    private AgentProfile convertToAgentProfile(Agent agent) {
        AgentProfile.RoleType rt = null;
        try {
            if (agent.getRoleType() != null) {
                rt = AgentProfile.RoleType.valueOf(agent.getRoleType());
            }
        } catch (Exception ignored) {}

        return AgentProfile.builder()
                .id(String.valueOf(agent.getId()))
                .name(agent.getName())
                .systemPrompt(agent.getSystemPrompt())
                .modelName(agent.getModel() != null ? agent.getModel() : getModelNameFromProvider(agent.getProviderId()))
                .temperature(agent.getTemperature() != null ? agent.getTemperature().doubleValue() : 0.7)
                .description(agent.getDescription())
                .enabled(true)
                .maxTokens(agent.getMaxTokens())
                .roleType(rt == null ? AgentProfile.RoleType.WORKER : rt)
                .build();
    }
    
    /**
     * 根据 Provider ID 获取模型名称
     */
    private String getModelNameFromProvider(Long providerId) {
        if (providerId == null) {
            return "gpt-4o";  // 默认模型
        }
        
        try {
            var models = channelModelService.listByChannelId(providerId);
            if (models != null && !models.isEmpty()) {
                return models.get(0).getModelValue();
            }
            return "gpt-4o";
        } catch (Exception e) {
            log.warn("Failed to get model name for provider: {}", providerId);
            return "gpt-4o";
        }
    }
}
