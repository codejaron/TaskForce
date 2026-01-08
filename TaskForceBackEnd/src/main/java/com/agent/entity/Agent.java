package com.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 智能体实体
 * 定义 AI 角色的配置和行为
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agents")
public class Agent {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("provider_id")
    private Long providerId; // 关联的 LLM 渠道
    
    @TableField("name")
    private String name;
    
    @TableField("system_prompt")
    private String systemPrompt; // 系统提示词

    @TableField("model")
    private String model; // 指定具体模型名称（例如 gpt-4o、gpt-4o-mini 等)

    @TableField("temperature")
    @Builder.Default
    private BigDecimal temperature = new BigDecimal("0.70");
    
    @TableField("max_tokens")
    @Builder.Default
    private Integer maxTokens = 4096;
    
    @TableField("description")
    private String description;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // Role type stored as string in DB: MODERATOR / WORKER
    @TableField("role_type")
    @Builder.Default
    private String roleType = "WORKER";
}
