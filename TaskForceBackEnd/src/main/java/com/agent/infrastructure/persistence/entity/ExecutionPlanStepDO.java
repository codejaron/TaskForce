package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行计划步骤数据库实体
 */
@Data
@TableName("execution_plan_step")
public class ExecutionPlanStepDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("plan_id")
    private String planId;

    @TableField("session_id")
    private String sessionId;

    @TableField("step_id")
    private String stepId;

    @TableField("step_index")
    private Integer stepIndex;

    @TableField("layer_index")
    private Integer layerIndex;

    @TableField("assigned_agent_id")
    private Long assignedAgentId;

    @TableField("assigned_agent_name")
    private String assignedAgentName;

    @TableField("instruction")
    private String instruction;

    @TableField("expected_output")
    private String expectedOutput;

    @TableField("depends_on")
    private String dependsOn;

    @TableField("status")
    private String status;

    @TableField("blocked_reason")
    private String blockedReason;

    @TableField("output_summary")
    private String outputSummary;

    @Version
    @TableField("version")
    private Integer version;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
