package com.agent.infrastructure.persistence.repository;

import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PauseSource;
import com.agent.domain.orchestration.model.PlanStatus;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.model.StepStatus;
import com.agent.domain.orchestration.repository.PlanRepository;
import com.agent.infrastructure.persistence.entity.ExecutionPlanDO;
import com.agent.infrastructure.persistence.entity.ExecutionPlanStepDO;
import com.agent.infrastructure.persistence.mapper.ExecutionPlanMapper;
import com.agent.infrastructure.persistence.mapper.ExecutionPlanStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 执行计划仓储实现
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PlanRepositoryImpl implements PlanRepository {

    private final ExecutionPlanMapper executionPlanMapper;
    private final ExecutionPlanStepMapper executionPlanStepMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<ExecutionPlan> findBySessionId(String sessionId) {
        return executionPlanMapper.findBySessionId(sessionId)
                .map(this::toDomain);
    }

    @Override
    public Optional<ExecutionPlan> findByPlanId(String planId) {
        ExecutionPlanDO entity = executionPlanMapper.selectById(planId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    @Transactional
    public ExecutionPlan save(ExecutionPlan plan) {
        ExecutionPlanDO entity = toEntity(plan);

        ExecutionPlanDO existing = executionPlanMapper.selectById(plan.getPlanId());
        if (existing != null) {
            executionPlanMapper.updateById(entity);
        } else {
            executionPlanMapper.insert(entity);
        }

        // 保存步骤到 execution_plan_step 表
        if (plan.getSteps() != null && !plan.getSteps().isEmpty()) {
            // 删除旧的步骤
            executionPlanStepMapper.delete(
                    new QueryWrapper<ExecutionPlanStepDO>().eq("plan_id", plan.getPlanId())
            );

            // 插入新的步骤
            for (PlanStep step : plan.getSteps()) {
                ExecutionPlanStepDO stepEntity = toStepEntity(step, plan.getPlanId(), plan.getSessionId());
                executionPlanStepMapper.insert(stepEntity);
            }
        }

        return plan;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        executionPlanMapper.delete(
                new QueryWrapper<ExecutionPlanDO>().eq("session_id", sessionId)
        );
    }

    @Override
    public boolean existsBySessionId(String sessionId) {
        return executionPlanMapper.existsBySessionId(sessionId);
    }

    /**
     * 转换为领域模型
     */
    private ExecutionPlan toDomain(ExecutionPlanDO entity) {
        try {
            // 从 execution_plan_step 表查询步骤
            List<ExecutionPlanStepDO> stepEntities = executionPlanStepMapper.findByPlanId(entity.getPlanId());
            List<PlanStep> steps = stepEntities.stream()
                    .map(this::toStepDomain)
                    .collect(Collectors.toList());

            return ExecutionPlan.builder()
                    .planId(entity.getPlanId())
                    .sessionId(entity.getSessionId())
                    .goal(entity.getGoal())
                    .status(PlanStatus.valueOf(entity.getStatus()))
                    .currentStepIndex(entity.getCurrentStepIndex() != null ? entity.getCurrentStepIndex() : 0)
                    .pauseReason(entity.getPauseReason())
                    .pausedBy(entity.getPausedBy() != null ? PauseSource.valueOf(entity.getPausedBy()) : null)
                    .pausedAtStepIndex(entity.getPausedAtStepIndex())
                    .pausedAgentId(entity.getPausedAgentId())
                    .pendingQuestion(entity.getPendingQuestion())
                    .replanCount(entity.getReplanCount() != null ? entity.getReplanCount() : 0)
                    .steps(steps)
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build();
        } catch (Exception e) {
            log.error("[PlanRepositoryImpl] Failed to convert entity to domain", e);
            throw new RuntimeException("Failed to convert ExecutionPlanDO to ExecutionPlan", e);
        }
    }

    /**
     * 转换为数据库实体
     */
    private ExecutionPlanDO toEntity(ExecutionPlan plan) {
        try {
            ExecutionPlanDO entity = new ExecutionPlanDO();
            entity.setPlanId(plan.getPlanId());
            entity.setSessionId(plan.getSessionId());
            entity.setGoal(plan.getGoal());
            entity.setStatus(plan.getStatus().name());
            entity.setCurrentStepIndex(plan.getCurrentStepIndex());
            entity.setPauseReason(plan.getPauseReason());
            entity.setPausedBy(plan.getPausedBy() != null ? plan.getPausedBy().name() : null);
            entity.setPausedAtStepIndex(plan.getPausedAtStepIndex());
            entity.setPausedAgentId(plan.getPausedAgentId());
            entity.setPendingQuestion(plan.getPendingQuestion());
            entity.setReplanCount(plan.getReplanCount());
            entity.setVersion(0); // 初始版本号
            entity.setCurrentLayerIndex(0); // 初始层级索引

            entity.setCreatedAt(plan.getCreatedAt() != null ? plan.getCreatedAt() : LocalDateTime.now());
            entity.setUpdatedAt(plan.getUpdatedAt() != null ? plan.getUpdatedAt() : LocalDateTime.now());

            return entity;
        } catch (Exception e) {
            log.error("[PlanRepositoryImpl] Failed to convert domain to entity", e);
            throw new RuntimeException("Failed to convert ExecutionPlan to ExecutionPlanDO", e);
        }
    }

    /**
     * 转换步骤为数据库实体
     */
    private ExecutionPlanStepDO toStepEntity(PlanStep step, String planId, String sessionId) {
        try {
            ExecutionPlanStepDO entity = new ExecutionPlanStepDO();
            entity.setPlanId(planId);
            entity.setSessionId(sessionId);
            entity.setStepId(step.getStepId());
            entity.setStepIndex(step.getStepIndex());
            entity.setLayerIndex(step.getLayerIndex());
            entity.setAssignedAgentId(step.getAssignedAgentId() != null ? Long.parseLong(step.getAssignedAgentId()) : null);
            entity.setAssignedAgentName(step.getAssignedAgentName());
            entity.setInstruction(step.getInstruction());
            entity.setExpectedOutput(step.getExpectedOutput());

            // 将 dependsOn 列表序列化为 JSON
            if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
                entity.setDependsOn(objectMapper.writeValueAsString(step.getDependsOn()));
            }

            entity.setStatus(step.getStatus().name());
            entity.setBlockedReason(step.getBlockedReason());
            entity.setVersion(0); // 初始版本号

            return entity;
        } catch (Exception e) {
            log.error("[PlanRepositoryImpl] Failed to convert step to entity", e);
            throw new RuntimeException("Failed to convert PlanStep to ExecutionPlanStepDO", e);
        }
    }

    /**
     * 转换步骤为领域模型
     */
    private PlanStep toStepDomain(ExecutionPlanStepDO entity) {
        try {
            // 解析 dependsOn JSON
            List<String> dependsOn = null;
            if (entity.getDependsOn() != null && !entity.getDependsOn().isEmpty()) {
                dependsOn = objectMapper.readValue(entity.getDependsOn(), new TypeReference<List<String>>() {});
            }

            return PlanStep.builder()
                    .stepId(entity.getStepId())
                    .stepIndex(entity.getStepIndex())
                    .layerIndex(entity.getLayerIndex())
                    .assignedAgentId(entity.getAssignedAgentId() != null ? entity.getAssignedAgentId().toString() : null)
                    .assignedAgentName(entity.getAssignedAgentName())
                    .instruction(entity.getInstruction())
                    .expectedOutput(entity.getExpectedOutput())
                    .dependsOn(dependsOn)
                    .status(StepStatus.valueOf(entity.getStatus()))
                    .blockedReason(entity.getBlockedReason())
                    .build();
        } catch (Exception e) {
            log.error("[PlanRepositoryImpl] Failed to convert step entity to domain", e);
            throw new RuntimeException("Failed to convert ExecutionPlanStepDO to PlanStep", e);
        }
    }
}
