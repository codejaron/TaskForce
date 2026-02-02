package com.agent.api.response;

import com.agent.domain.tool.AgentToolDetail;
import com.agent.infrastructure.persistence.entity.Agent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 响应 DTO
 * 包含 Agent 基本信息和已配置的 MCP 工具列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private Long id;
    private Long providerId;
    private String name;
    private String model;
    private String systemPrompt;
    private BigDecimal temperature;
    private Integer maxTokens;
    private String description;
    private String roleType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // MCP工具配置
    private List<String> selectedMcpTools; // 已配置的工具ID列表
    private List<AgentToolDetail> toolDetails; // 工具详情（包含启用状态）

    /**
     * 从 Agent 实体创建响应 DTO
     */
    public static AgentResponse fromAgent(Agent agent) {
        return AgentResponse.builder()
                .id(agent.getId())
                .providerId(agent.getProviderId())
                .name(agent.getName())
                .model(agent.getModel())
                .systemPrompt(agent.getSystemPrompt())
                .temperature(agent.getTemperature())
                .maxTokens(agent.getMaxTokens())
                .description(agent.getDescription())
                .roleType(agent.getRoleType())
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }

    /**
     * 从 Agent 实体创建响应 DTO，并包含工具信息
     */
    public static AgentResponse fromAgentWithTools(Agent agent, List<String> selectedMcpTools) {
        AgentResponse response = fromAgent(agent);
        response.setSelectedMcpTools(selectedMcpTools);
        return response;
    }

    /**
     * 从 Agent 实体创建响应 DTO，并包含完整工具详情
     */
    public static AgentResponse fromAgentWithToolDetails(Agent agent, List<AgentToolDetail> toolDetails) {
        AgentResponse response = fromAgent(agent);
        response.setToolDetails(toolDetails);
        // 同时提取工具ID列表（仅启用的）
        if (toolDetails != null) {
            response.setSelectedMcpTools(
                    toolDetails.stream()
                            .filter(AgentToolDetail::getEnabled)
                            .map(AgentToolDetail::getToolId)
                            .toList()
            );
        }
        return response;
    }
}
