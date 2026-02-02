package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent-MCP工具关联实体
 * 实现多对多关系，支持工具启用/禁用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_tools")
public class AgentTool {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("agent_id")
    private Long agentId;

    @TableField("tool_id")
    private String toolId; // 格式: serverId::toolName

    @TableField("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @TableField(value = "added_at", fill = FieldFill.INSERT)
    private LocalDateTime addedAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
