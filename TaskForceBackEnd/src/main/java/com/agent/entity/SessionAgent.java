package com.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话-智能体关联实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_agents")
public class SessionAgent {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("session_id")
    private String sessionId;
    
    @TableField("agent_id")
    private Long agentId;
    
    @TableField("role_in_session")
    private String roleInSession;
    
    @TableField("is_admin")
    private Boolean isAdmin;
    
    @TableField("join_order")
    private Integer joinOrder;
    
    @TableField(value = "joined_at", fill = FieldFill.INSERT)
    private LocalDateTime joinedAt;
}
