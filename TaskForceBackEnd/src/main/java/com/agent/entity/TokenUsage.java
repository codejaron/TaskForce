package com.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Token使用统计实体
 * 用于记录每次LLM调用的token消耗和成本
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("token_usage")
public class TokenUsage {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * LLM Provider ID
     */
    @TableField("provider_id")
    private Long providerId;

    /**
     * 模型名称（如gpt-4o, claude-sonnet-3.5等）
     */
    @TableField("model_name")
    private String modelName;

    /**
     * 提示Token数（输入）
     */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    /**
     * 完成Token数（输出）
     */
    @TableField("completion_tokens")
    private Integer completionTokens;

    /**
     * 总Token数
     */
    @TableField("total_tokens")
    private Integer totalTokens;

    /**
     * 成本（单位：美元）
     */
    @TableField("cost")
    private BigDecimal cost;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
