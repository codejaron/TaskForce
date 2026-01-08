package com.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MCP Server 定义
 * 描述如何连接到一个 MCP Server
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerDefinition {

    /**
     * Server 唯一标识
     */
    private String id;

    /**
     * Server 显示名称
     */
    private String name;

    /**
     * 连接类型
     */
    private McpServerType type;

    /**
     * STDIO 模式的启动命令（如：npx, python）
     */
    private String command;

    /**
     * STDIO 模式的命令参数
     */
    private List<String> args;

    /**
     * SSE 模式的 URL
     */
    private String sseUrl;

    /**
     * Server 描述
     */
    private String description;

    /**
     * 是否已连接
     */
    @Builder.Default
    private Boolean connected = false;

    /**
     * MCP Server 连接类型枚举
     */
    public enum McpServerType {
        /**
         * 标准输入输出模式（本地进程）
         */
        STDIO,
        /**
         * Server-Sent Events 模式（远程服务）
         */
        SSE
    }
}

