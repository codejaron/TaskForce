package com.agent.mcpserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具定义 VO（View Object）
 * 用于 Controller 层返回，保持与前端和 Host 端的 API 兼容性
 * 内部使用 Spring AI 的 McpSchema.Tool，但对外暴露此 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolVO {

    /**
     * 工具名称（唯一标识）
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 输入参数 JSON Schema
     */
    private Object inputSchema;

    /**
     * 工具来源类型
     */
    private String sourceType;

    /**
     * 所属 Provider ID
     */
    private String providerId;
}
