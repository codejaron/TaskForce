package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A2A 会话实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sessions")
public class Session {
    
    @TableId(value = "id", type = IdType.INPUT)
    private String id;  // UUID
    
    @TableField("name")
    private String name;
    
    @TableField("type")
    private String type;  // SINGLE/GROUP
    
    @TableField("status")
    private String status;  // PENDING/RUNNING/PAUSED/COMPLETED
    
    @TableField("config")
    private String config;  // JSON
    
    @TableField("max_rounds")
    private Integer maxRounds;
    
    @TableField("current_round")
    private Integer currentRound;
    
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
