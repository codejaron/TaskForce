package com.agent.application.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 计划步骤 DTO
 * 用于 BeanOutputParser 自动解析
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanStepDTO {

    /**
     * 步骤序号（从 1 开始）
     */
    @JsonProperty("stepIndex")
    private int stepIndex;

    /**
     * 步骤描述
     */
    @JsonProperty("description")
    private String description;

    /**
     * 分配的 Agent ID
     */
    @JsonProperty("assignedAgentId")
    private String assignedAgentId;

    /**
     * 所需能力
     */
    @JsonProperty("requiredCapability")
    private String requiredCapability;

    /**
     * 详细执行指令
     */
    @JsonProperty("instruction")
    private String instruction;

    /**
     * 期望输出格式
     */
    @JsonProperty("expectedOutput")
    private String expectedOutput;
}
