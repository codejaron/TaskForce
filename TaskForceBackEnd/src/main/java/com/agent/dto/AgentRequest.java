package com.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

/**
 * Agent 创建/更新请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    @NotBlank(message = "智能体名称不能为空")
    private String name;

    private Long providerId; // 关联的LLM渠道ID

    private String model; // 模型标识 (例如 gpt-4o)

    private String modelName; // 前端发送的模型名称（映射到 model）

    private String systemPrompt;

    private BigDecimal temperature;

    private Integer maxTokens;

    private String description;

    private String roleType; // MODERATOR/WORKER

    private List<String> selectedMcpTools; // MCP工具ID列表 (格式: serverId::toolName)

    /**
     * 获取模型标识（优先使用 model，其次使用 modelName）
     */
    public String getEffectiveModel() {
        return model != null ? model : modelName;
    }
}
