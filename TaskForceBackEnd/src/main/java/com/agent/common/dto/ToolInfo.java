package com.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具信息
 * 用于前端工具市场展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInfo {

    /**
     * 工具唯一标识（格式：serverId::toolName）
     */
    private String id;

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 所属 MCP Server ID
     */
    private String serverId;

    /**
     * 所属 MCP Server 名称
     */
    private String serverName;

    /**
     * 工具输入参数 Schema (JSON 格式)
     */
    private String inputSchema;
}
