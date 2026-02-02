package com.agent.domain.token;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Provider成本统计DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderCostDTO {
    private Long providerId;
    private String providerName;
    private BigDecimal totalCost;
    private Long totalTokens;
    private Integer callCount;
}
