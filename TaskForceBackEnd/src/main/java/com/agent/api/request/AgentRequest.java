package com.agent.api.request;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    /**
     * 模型标识（例如 gpt-4o / deepseek-chat）。
     *
     * 兼容：历史前端可能会发送 modelName 字段（与 model 同义），通过 JsonAlias 让其自动映射到 model。
     */
    @JsonAlias({"modelName"})
    private String model;

    private String systemPrompt;

    private BigDecimal temperature;

    private Integer maxTokens;

    private String description;

    private String roleType; // MODERATOR/WORKER

    private List<String> selectedMcpTools; // MCP工具ID列表 (格式: serverId::toolName)
}
