package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行计划数据库实体
 */
@Data
@TableName("execution_plan")
public class ExecutionPlanDO {

    @TableId("plan_id")
    private String planId;

    @TableField("session_id")
    private String sessionId;

    private String goal;

    private String status;

    @TableField("current_step_index")
    private Integer currentStepIndex;

    @TableField("pause_reason")
    private String pauseReason;

    @TableField("paused_by")
    private String pausedBy;

    @TableField("paused_at_step_index")
    private Integer pausedAtStepIndex;

    @TableField("paused_agent_id")
    private String pausedAgentId;

    @TableField("pending_question")
    private String pendingQuestion;

    @TableField("replan_count")
    private Integer replanCount;

    @Version
    @TableField("version")
    private Integer version;

    @TableField("current_layer_index")
    private Integer currentLayerIndex;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
