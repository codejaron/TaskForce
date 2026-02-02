package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工具调用记录实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tool_calls")
public class ToolCall {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("step_id")
    private String stepId;

    @TableField("agent_id")
    private Long agentId;

    @TableField("tool_call_id")
    private String toolCallId;

    @TableField("tool_name")
    private String toolName;

    @TableField("server_name")
    private String serverName;

    @TableField("tool_args")
    private String toolArgs;

    @TableField("tool_result")
    private String toolResult;

    @TableField("status")
    private String status;  // RUNNING/SUCCESS/FAILED

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("sequence")
    private Integer sequence;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
