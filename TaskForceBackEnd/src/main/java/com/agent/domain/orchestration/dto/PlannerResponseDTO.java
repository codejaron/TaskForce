package com.agent.domain.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

/**
 * Planner 响应 DTO
 * 用于 BeanOutputParser 自动解析
 *
 * 支持三种响应类型：
 * 1. type="plan": 成功生成计划
 * 2. type="question": 需要用户澄清
 * 3. type="cannot_plan": 无法完成
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlannerResponseDTO {

    /**
     * 响应类型: "plan" | "question" | "cannot_plan"
     */
    @JsonProperty("type")
    private String type;

    /**
     * 用户目标描述（当 type=plan 时必填）
     */
    @JsonProperty("goal")
    private String goal;

    /**
     * 步骤列表（当 type=plan 时必填）
     */
    @JsonProperty("steps")
    private List<PlanStepDTO> steps;

    /**
     * 问题内容（当 type=question 时必填）
     */
    @JsonProperty("content")
    private String content;

    /**
     * 失败原因（当 type=cannot_plan 时必填）
     */
    @JsonProperty("reason")
    private String reason;
}
