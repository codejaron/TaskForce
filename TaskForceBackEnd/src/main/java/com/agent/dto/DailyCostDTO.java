package com.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日成本统计DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyCostDTO {
    private LocalDate date;
    private BigDecimal totalCost;
    private Long totalTokens;
    private Integer callCount;
}
