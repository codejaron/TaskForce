package com.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.agent.dto.AgentToolDetail;
import com.agent.entity.AgentTool;
import com.agent.mapper.AgentToolMapper;
import com.agent.mcp.McpToolRegistry;
import com.agent.model.ToolInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent工具关联服务
 * <p>
 * 职责：
 * 1. 管理Agent与MCP工具的多对多关联
 * 2. 支持工具的启用/禁用
 * 3. 验证工具有效性（工具必须在McpToolRegistry中存在）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolService {

    private final AgentToolMapper agentToolMapper;
    private final McpToolRegistry mcpToolRegistry;

    /**
     * 为Agent添加工具
     *
     * @param agentId Agent ID
     * @param toolId  工具ID（格式：serverId::toolName）
     * @return 创建的关联记录
     * @throws RuntimeException 如果工具不存在
     */
    @Transactional
    public AgentTool addToolToAgent(Long agentId, String toolId) {
        // 1. 验证工具是否存在
        if (mcpToolRegistry.getToolCallback(toolId).isEmpty()) {
            throw new RuntimeException("Tool not found in registry: " + toolId);
        }

        // 2. 检查是否已存在
        LambdaQueryWrapper<AgentTool> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentTool::getAgentId, agentId)
                .eq(AgentTool::getToolId, toolId);

        AgentTool existing = agentToolMapper.selectOne(wrapper);
        if (existing != null) {
            log.info("Tool already associated with agent: agentId={}, toolId={}", agentId, toolId);
            return existing;
        }

        // 3. 创建关联
        AgentTool agentTool = AgentTool.builder()
                .agentId(agentId)
                .toolId(toolId)
                .enabled(true)
                .build();

        agentToolMapper.insert(agentTool);
        log.info("Tool added to agent: agentId={}, toolId={}", agentId, toolId);
        return agentTool;
    }

    /**
     * 批量添加工具（替换现有工具）
     *
     * @param agentId Agent ID
     * @param toolIds 工具ID列表
     */
    @Transactional
    public void setAgentTools(Long agentId, List<String> toolIds) {
        // 1. 删除现有关联
        LambdaQueryWrapper<AgentTool> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(AgentTool::getAgentId, agentId);
        agentToolMapper.delete(deleteWrapper);

        // 2. 如果工具列表为空，直接返回（允许清空工具）
        if (toolIds == null || toolIds.isEmpty()) {
            log.info("Agent tools cleared: agentId={}", agentId);
            return;
        }

        // 3. 验证并插入新工具
        int validCount = 0;
        for (String toolId : toolIds) {
            log.debug("Checking tool: {}", toolId);

            // 验证工具是否存在
            var callbackOpt = mcpToolRegistry.getToolCallback(toolId);
            if (callbackOpt.isEmpty()) {
                log.warn("Tool not found in registry, skipping: {} (agentId={})", toolId, agentId);
                continue; // 跳过不存在的工具，而不是抛异常
            }

            // 插入关联记录
            AgentTool agentTool = AgentTool.builder()
                    .agentId(agentId)
                    .toolId(toolId)
                    .enabled(true)
                    .build();
            agentToolMapper.insert(agentTool);
            validCount++;
            log.debug("Tool added successfully: {} (agentId={})", toolId, agentId);
        }

        log.info("Agent tools updated: agentId={}, requested={}, valid={}", agentId, toolIds.size(), validCount);
    }

    /**
     * 从Agent移除工具
     *
     * @param agentId Agent ID
     * @param toolId  工具ID
     */
    @Transactional
    public void removeToolFromAgent(Long agentId, String toolId) {
        LambdaQueryWrapper<AgentTool> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentTool::getAgentId, agentId)
                .eq(AgentTool::getToolId, toolId);

        agentToolMapper.delete(wrapper);
        log.info("Tool removed from agent: agentId={}, toolId={}", agentId, toolId);
    }

    /**
     * 启用/禁用工具
     *
     * @param agentId Agent ID
     * @param toolId  工具ID
     * @param enabled 是否启用
     */
    @Transactional
    public void setToolEnabled(Long agentId, String toolId, boolean enabled) {
        LambdaUpdateWrapper<AgentTool> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AgentTool::getAgentId, agentId)
                .eq(AgentTool::getToolId, toolId)
                .set(AgentTool::getEnabled, enabled);

        agentToolMapper.update(null, wrapper);
        log.info("Tool status updated: agentId={}, toolId={}, enabled={}", agentId, toolId, enabled);
    }

    /**
     * 获取Agent的所有工具ID（仅包括启用的）
     *
     * @param agentId Agent ID
     * @return 启用的工具ID列表
     */
    public List<String> getEnabledToolIds(Long agentId) {
        LambdaQueryWrapper<AgentTool> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentTool::getAgentId, agentId)
                .eq(AgentTool::getEnabled, true);

        return agentToolMapper.selectList(wrapper).stream()
                .map(AgentTool::getToolId)
                .collect(Collectors.toList());
    }

    /**
     * 获取Agent的所有工具详情（包括禁用的）
     *
     * @param agentId Agent ID
     * @return 工具详情列表（带启用状态）
     */
    public List<AgentToolDetail> getAgentToolDetails(Long agentId) {
        LambdaQueryWrapper<AgentTool> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentTool::getAgentId, agentId);

        List<AgentTool> agentTools = agentToolMapper.selectList(wrapper);

        // 获取所有可用工具的完整信息
        List<ToolInfo> availableTools = mcpToolRegistry.listAvailableTools();

        return agentTools.stream()
                .map(at -> {
                    // 从完整工具列表中查找匹配的工具（包含所有6个字段）
                    ToolInfo toolInfo = availableTools.stream()
                            .filter(tool -> tool.getId().equals(at.getToolId()))
                            .findFirst()
                            .orElse(null); // 如果工具已被删除，返回null

                    return AgentToolDetail.builder()
                            .id(at.getId())
                            .agentId(at.getAgentId())
                            .toolId(at.getToolId())
                            .enabled(at.getEnabled())
                            .toolInfo(toolInfo) // 现在包含完整的ToolInfo（id, name, description, serverId, serverName, inputSchema）
                            .addedAt(at.getAddedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 清理无效的工具关联（工具已从注册表中移除）
     *
     * @param agentId Agent ID
     * @return 清理的记录数
     */
    @Transactional
    public int cleanupInvalidTools(Long agentId) {
        List<AgentTool> agentTools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getAgentId, agentId)
        );

        int cleaned = 0;
        for (AgentTool agentTool : agentTools) {
            if (mcpToolRegistry.getToolCallback(agentTool.getToolId()).isEmpty()) {
                agentToolMapper.deleteById(agentTool.getId());
                cleaned++;
                log.warn("Cleaned invalid tool: agentId={}, toolId={}", agentId, agentTool.getToolId());
            }
        }

        return cleaned;
    }

    /**
     * 删除指定服务器的所有工具关联
     * 当MCP服务器被删除时调用，清理所有Agent中对该服务器工具的引用
     *
     * @param serverId 服务器ID
     * @return 删除的记录数
     */
    @Transactional
    public int removeToolsByServerId(String serverId) {
        // 查询所有以 serverId:: 开头的工具关联
        LambdaQueryWrapper<AgentTool> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(AgentTool::getToolId, serverId + "::");

        List<AgentTool> toolsToDelete = agentToolMapper.selectList(wrapper);
        int deletedCount = toolsToDelete.size();

        if (deletedCount > 0) {
            agentToolMapper.delete(wrapper);
            log.info("Removed {} tool associations for server: {}", deletedCount, serverId);

            // 记录被删除的工具详情
            toolsToDelete.forEach(tool ->
                log.debug("Removed tool association: agentId={}, toolId={}", tool.getAgentId(), tool.getToolId())
            );
        } else {
            log.info("No tool associations found for server: {}", serverId);
        }

        return deletedCount;
    }
}
