package com.agent.domain.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

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
     * 分配的 Agent ID
     */
    @JsonProperty("assignedAgentId")
    private String assignedAgentId;

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

    /**
     * 依赖的步骤索引列表（用于并行执行）
     * 例如：[1, 2] 表示当前步骤依赖步骤 1 和步骤 2 完成后才能执行
     */
    @JsonProperty("dependsOn")
    private List<Integer> dependsOn;
}
