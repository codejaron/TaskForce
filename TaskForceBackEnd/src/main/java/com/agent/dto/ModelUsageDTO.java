package com.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 模型使用统计DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelUsageDTO {
    private String modelName;
    private Long totalPromptTokens;
    private Long totalCompletionTokens;
    private Long totalTokens;
    private BigDecimal totalCost;
    private Integer callCount;
}
