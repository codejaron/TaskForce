package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("messages")
public class Message {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("session_id")
    private String sessionId;
    
    @TableField("agent_id")
    private Long agentId;

    @TableField("agent_name")
    private String agentName;
    
    @TableField("content")
    private String content;
    
    @TableField("message_type")
    private String messageType;  // text/tool_use/tool_result
    
    @TableField("role")
    private String role;  // user/assistant/system
    
    @TableField("status")
    private String status;  // STREAMING, COMPLETED
    
    @TableField("step_id")
    private String stepId;  // 关联的步骤ID，用于关联工具调用
    
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
