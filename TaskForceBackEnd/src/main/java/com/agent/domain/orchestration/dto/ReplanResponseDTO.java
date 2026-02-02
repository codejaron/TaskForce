package com.agent.domain.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

/**
 * Replanner 响应 DTO
 * 用于 BeanOutputParser 自动解析重规划响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplanResponseDTO {

    /**
     * 类型（通常是 "plan"）
     */
    @JsonProperty("type")
    private String type;

    /**
     * 调整后的目标
     */
    @JsonProperty("goal")
    private String goal;

    /**
     * 步骤列表
     */
    @JsonProperty("steps")
    private List<PlanStepDTO> steps;
}
