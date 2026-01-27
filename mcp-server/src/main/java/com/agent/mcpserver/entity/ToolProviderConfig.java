package com.agent.mcpserver.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工具提供者配置实体
 * 支持三种类型：STDIO、NATIVE、REMOTE_SSE
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tool_provider_config")
public class ToolProviderConfig {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 提供者名称
     */
    private String name;

    /**
     * 提供者类型：STDIO, NATIVE, REMOTE_SSE
     */
    private ProviderType type;

    /**
     * 是否启用
     */
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 描述
     */
    private String description;

    // ========== STDIO 类型配置 ==========

    /**
     * 启动命令（如：npx, python）
     */
    private String command;

    /**
     * 命令参数（JSON 数组）
     */
    private String args;

    /**
     * 环境变量（JSON 对象）
     */
    private String env;

    // ========== REMOTE_SSE 类型配置 ==========

    /**
     * 远程 SSE 服务 URL
     */
    private String sseUrl;

    /**
     * 请求头（JSON 对象）
     */
    private String headers;

    /**
     * 超时时间（秒）
     */
    @Builder.Default
    private Integer timeout = 30;

    // ========== 通用字段 ==========

    /**
     * 连接状态
     */
    @Builder.Default
    private Boolean connected = false;

    /**
     * 工具数量
     */
    @Builder.Default
    private Integer toolCount = 0;

    /**
     * 最后连接时间
     */
    private LocalDateTime lastConnectedAt;

    /**
     * 错误信息
     */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 提供者类型枚举
     */
    public enum ProviderType {
        /**
         * STDIO 模式（npx 子进程）
         */
        STDIO,

        /**
         * 远程 SSE 服务
         */
        REMOTE_SSE
    }
}
