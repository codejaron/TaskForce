package com.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 会话成本统计DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCostDTO {
    private String sessionId;
    private String sessionName;
    private BigDecimal totalCost;
    private Long totalTokens;
    private Integer callCount;
}
