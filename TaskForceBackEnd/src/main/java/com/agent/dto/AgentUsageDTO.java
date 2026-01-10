package com.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Agent使用统计DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentUsageDTO {
    private Long agentId;
    private String agentName;
    private Long totalPromptTokens;
    private Long totalCompletionTokens;
    private Long totalTokens;
    private BigDecimal totalCost;
    private Integer callCount;
}
