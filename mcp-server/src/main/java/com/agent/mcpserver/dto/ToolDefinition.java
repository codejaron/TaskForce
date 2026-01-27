package com.agent.mcpserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具定义 DTO
 * 符合 MCP 协议规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

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
    private ToolSourceType sourceType;

    /**
     * 所属 Provider ID
     */
    private String providerId;

    /**
     * 工具来源类型枚举
     */
    public enum ToolSourceType {
        /**
         * STDIO 模式（npx 等子进程）
         */
        STDIO,
        
        /**
         * 原生 Java 工具
         */
        NATIVE,
        
        /**
         * 远程 SSE 服务
         */
        REMOTE_SSE
    }
}
