package com.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Agent成本统计DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCostDTO {
    private Long agentId;
    private String agentName;
    private BigDecimal totalCost;
    private Long totalTokens;
    private Integer callCount;
}
