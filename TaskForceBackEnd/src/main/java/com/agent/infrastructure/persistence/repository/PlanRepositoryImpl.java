package com.agent.infrastructure.persistence.repository;

import com.agent.domain.plan.ExecutionPlan;
import com.agent.domain.plan.PauseSource;
import com.agent.domain.plan.PlanStatus;
import com.agent.domain.plan.PlanStep;
import com.agent.domain.plan.PlanRepository;
import com.agent.infrastructure.persistence.entity.ExecutionPlanDO;
import com.agent.infrastructure.persistence.mapper.ExecutionPlanMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 执行计划仓储实现
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PlanRepositoryImpl implements PlanRepository {

    private final ExecutionPlanMapper executionPlanMapper;
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
    public ExecutionPlan save(ExecutionPlan plan) {
        ExecutionPlanDO entity = toEntity(plan);

        ExecutionPlanDO existing = executionPlanMapper.selectById(plan.getPlanId());
        if (existing != null) {
            executionPlanMapper.updateById(entity);
        } else {
            executionPlanMapper.insert(entity);
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
            List<PlanStep> steps = null;
            if (entity.getStepsJson() != null && !entity.getStepsJson().isEmpty()) {
                steps = objectMapper.readValue(entity.getStepsJson(), new TypeReference<List<PlanStep>>() {});
            }

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

            if (plan.getSteps() != null) {
                entity.setStepsJson(objectMapper.writeValueAsString(plan.getSteps()));
            }

            entity.setCreatedAt(plan.getCreatedAt() != null ? plan.getCreatedAt() : LocalDateTime.now());
            entity.setUpdatedAt(plan.getUpdatedAt() != null ? plan.getUpdatedAt() : LocalDateTime.now());

            return entity;
        } catch (Exception e) {
            log.error("[PlanRepositoryImpl] Failed to convert domain to entity", e);
            throw new RuntimeException("Failed to convert ExecutionPlan to ExecutionPlanDO", e);
        }
    }
}
