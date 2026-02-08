package com.agent.domain.orchestration.state;

import com.agent.domain.orchestration.model.TaskContext;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.model.StepStatus;
import com.agent.domain.orchestration.repository.PlanRepository;
import com.agent.infrastructure.persistence.entity.ExecutionPlanDO;
import com.agent.infrastructure.persistence.entity.ExecutionPlanStepDO;
import com.agent.infrastructure.persistence.entity.Message;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.ExecutionPlanMapper;
import com.agent.infrastructure.persistence.mapper.ExecutionPlanStepMapper;
import com.agent.infrastructure.persistence.mapper.MessageMapper;
import com.agent.service.AgentService;
import com.agent.service.MessageService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 状态管理器
 * 负责 ExecutionPlan 的加载/保存和事件记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateManager {

    private final PlanRepository planRepository;
    private final ExecutionPlanMapper executionPlanMapper;
    private final ExecutionPlanStepMapper executionPlanStepMapper;
    private final MessageMapper messageMapper;
    private final AgentService agentService;
    private final MessageService messageService;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final long PLAN_CACHE_TTL_MINUTES = 30;
    private static final String META_FIELD = "meta";

    private static String planCacheKey(String sessionId) {
        return "plan:" + sessionId;
    }

    private static String stepField(String stepId) {
        return "step:" + stepId;
    }

    // === Plan 操作 ===

    /**
     * 加载计划（从 Redis Hash 读取，miss 时查 DB 回填）
     */
    public ExecutionPlan loadPlan(String sessionId) {
        String cacheKey = planCacheKey(sessionId);

        try {
            // 1) 尝试从 Redis Hash 读取
            Map<Object, Object> hashEntries = redisTemplate.opsForHash().entries(cacheKey);

            if (!hashEntries.isEmpty()) {
                // 从 Redis Hash 重建 ExecutionPlan
                String metaJson = (String) hashEntries.get(META_FIELD);
                if (metaJson != null && !metaJson.isBlank()) {
                    ExecutionPlan plan = objectMapper.readValue(metaJson, ExecutionPlan.class);

                    // 重建步骤列表
                    List<PlanStep> steps = new ArrayList<>();
                    for (Map.Entry<Object, Object> entry : hashEntries.entrySet()) {
                        String key = (String) entry.getKey();
                        if (key.startsWith("step:")) {
                            String stepJson = (String) entry.getValue();
                            PlanStep step = objectMapper.readValue(stepJson, PlanStep.class);
                            steps.add(step);
                        }
                    }

                    // 按 stepIndex 排序
                    steps.sort(Comparator.comparingInt(PlanStep::getStepIndex));
                    plan.setSteps(steps);

                    return plan;
                }
            }
        } catch (Exception e) {
            log.warn("[StateManager] Redis Hash read failed, fallback to DB: sessionId={}", sessionId, e);
        }

        // 2) DB fallback
        ExecutionPlan plan = planRepository.findBySessionId(sessionId).orElse(null);

        // 3) 回填 Redis Hash
        if (plan != null) {
            cacheToRedisHash(sessionId, plan);
        }

        return plan;
    }

    /**
     * 更新步骤状态（同时写 Redis field 和 MySQL 行）
     */
    public void updateStepStatus(String sessionId, String stepId, StepStatus status, String blockedReason) {
        String cacheKey = planCacheKey(sessionId);
        String fieldKey = stepField(stepId);

        try {
            // 1) 更新 MySQL
            ExecutionPlanStepDO stepEntity = executionPlanStepMapper.findByPlanIdAndStepId(
                    loadPlan(sessionId).getPlanId(), stepId);

            if (stepEntity != null) {
                stepEntity.setStatus(status.name());
                stepEntity.setBlockedReason(blockedReason);
                stepEntity.setUpdatedAt(LocalDateTime.now());
                executionPlanStepMapper.updateById(stepEntity);
            }

            // 2) 更新 Redis Hash field
            String stepJson = redisTemplate.opsForHash().get(cacheKey, fieldKey).toString();
            if (stepJson != null && !stepJson.isBlank()) {
                PlanStep step = objectMapper.readValue(stepJson, PlanStep.class);
                step.setStatus(status);
                step.setBlockedReason(blockedReason);

                String updatedJson = objectMapper.writeValueAsString(step);
                redisTemplate.opsForHash().put(cacheKey, fieldKey, updatedJson);
                redisTemplate.expire(cacheKey, PLAN_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            log.error("[StateManager] Failed to update step status: sessionId={}, stepId={}", sessionId, stepId, e);
        }
    }

    /**
     * 更新计划元数据（单线程更新 meta field 和 plan 主表）
     */
    public void updatePlanMeta(ExecutionPlan plan) {
        String cacheKey = planCacheKey(plan.getSessionId());

        try {
            // 1) 更新 MySQL（使用乐观锁）
            ExecutionPlanDO entity = executionPlanMapper.selectById(plan.getPlanId());
            if (entity != null) {
                entity.setStatus(plan.getStatus().name());
                entity.setCurrentStepIndex(plan.getCurrentStepIndex());
                entity.setPauseReason(plan.getPauseReason());
                entity.setPausedBy(plan.getPausedBy() != null ? plan.getPausedBy().name() : null);
                entity.setPausedAtStepIndex(plan.getPausedAtStepIndex());
                entity.setPausedAgentId(plan.getPausedAgentId());
                entity.setPendingQuestion(plan.getPendingQuestion());
                entity.setReplanCount(plan.getReplanCount());
                entity.setUpdatedAt(LocalDateTime.now());

                // MyBatis-Plus 会自动处理 @Version 乐观锁
                int updated = executionPlanMapper.updateById(entity);
                if (updated == 0) {
                    log.warn("[StateManager] Optimistic lock failed for plan: {}", plan.getPlanId());
                }
            }

            // 2) 更新 Redis Hash meta field
            String metaJson = objectMapper.writeValueAsString(plan);
            redisTemplate.opsForHash().put(cacheKey, META_FIELD, metaJson);
            redisTemplate.expire(cacheKey, PLAN_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        } catch (Exception e) {
            log.error("[StateManager] Failed to update plan meta: planId={}", plan.getPlanId(), e);
        }
    }

    /**
     * 保存计划（完整保存，包括步骤）
     * 注意：这个方法主要用于创建新计划，日常更新应使用 updatePlanMeta() 和 updateStepStatus()
     */
    public void savePlan(ExecutionPlan plan) {
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);

        // 回填 Redis Hash
        cacheToRedisHash(plan.getSessionId(), plan);
    }

    /**
     * 删除计划（DB + Redis）
     */
    public void deletePlan(String sessionId) {
        planRepository.deleteBySessionId(sessionId);
        try {
            redisTemplate.delete(planCacheKey(sessionId));
        } catch (Exception e) {
            log.warn("[StateManager] Failed to evict plan cache: sessionId={}", sessionId, e);
        }
    }

    /**
     * 将计划缓存到 Redis Hash
     */
    private void cacheToRedisHash(String sessionId, ExecutionPlan plan) {
        String cacheKey = planCacheKey(sessionId);

        try {
            Map<String, String> hashMap = new HashMap<>();

            // 1) 存储 meta field
            String metaJson = objectMapper.writeValueAsString(plan);
            hashMap.put(META_FIELD, metaJson);

            // 2) 存储每个步骤为独立 field
            if (plan.getSteps() != null) {
                for (PlanStep step : plan.getSteps()) {
                    String stepJson = objectMapper.writeValueAsString(step);
                    hashMap.put(stepField(step.getStepId()), stepJson);
                }
            }

            // 3) 批量写入 Redis Hash
            redisTemplate.opsForHash().putAll(cacheKey, hashMap);
            redisTemplate.expire(cacheKey, PLAN_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        } catch (Exception e) {
            log.warn("[StateManager] Failed to cache plan to Redis Hash: sessionId={}", sessionId, e);
        }
    }

    // === 事件记录 ===

    /**
     * 记录用户输入
     */
    public Long recordUserInput(String sessionId, String requestId, String text) {
        try {
            Message msg = new Message();
            msg.setSessionId(sessionId);
            msg.setRole("user");
            msg.setMessageType("USER_INPUT");
            msg.setContent(text);
            msg.setCreatedAt(LocalDateTime.now());
            messageService.saveMessage(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("[StateManager] Failed to record user input", e);
            return null;
        }
    }

    /**
     * 记录 Planner 消息
     */
    public Long recordPlannerMessage(String sessionId, String requestId, String response) {
        try {
            Message msg = new Message();
            msg.setSessionId(sessionId);
            msg.setRole("assistant");
            msg.setMessageType("PLANNER_MSG");
            msg.setAgentName("Planner");
            msg.setContent(response);
            msg.setCreatedAt(LocalDateTime.now());
            messageService.saveMessage(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("[StateManager] Failed to record planner message", e);
            return null;
        }
    }

    /**
     * 记录步骤输出
     */
    public Long recordStepMessage(String sessionId, PlanStep step, String response) {
        try {
            Message msg = new Message();
            msg.setSessionId(sessionId);
            msg.setRole("assistant");
            msg.setMessageType("WORKER_MSG");
            msg.setAgentId(Long.parseLong(step.getAssignedAgentId()));
            msg.setAgentName(step.getAssignedAgentName());
            msg.setContent(response);
            msg.setStatus("COMPLETED");
            msg.setStepId(step.getStepId());  // 设置 stepId
            msg.setCreatedAt(LocalDateTime.now());
            messageService.saveMessage(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("[StateManager] Failed to record step message", e);
            return null;
        }
    }
    
    /**
     * 创建流式消息（Worker 开始执行时调用）
     */
    public Long createStreamingMessage(String sessionId, PlanStep step) {
        try {
            Message msg = new Message();
            msg.setSessionId(sessionId);
            msg.setRole("assistant");
            msg.setMessageType("WORKER_MSG");
            msg.setAgentId(Long.parseLong(step.getAssignedAgentId()));
            msg.setAgentName(step.getAssignedAgentName());
            msg.setContent("");  // 初始为空
            msg.setStatus("STREAMING");
            msg.setStepId(step.getStepId());  // 设置 stepId
            msg.setCreatedAt(LocalDateTime.now());
            messageService.saveMessage(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("[StateManager] Failed to create streaming message", e);
            return null;
        }
    }

    /**
     * 创建流式消息（Planner 使用，直接传入 agentId）
     */
    public Long createStreamingMessage(String sessionId, Long agentId, String agentName) {
        try {
            Message msg = new Message();
            msg.setSessionId(sessionId);
            msg.setRole("assistant");
            msg.setMessageType("PLANNER_MSG");
            msg.setAgentId(agentId);
            msg.setAgentName(agentName != null ? agentName : "Planner");
            msg.setContent("");  // 初始为空
            msg.setStatus("STREAMING");
            msg.setCreatedAt(LocalDateTime.now());
            messageService.saveMessage(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("[StateManager] Failed to create streaming message for planner", e);
            return null;
        }
    }

    /**
     * 完成流式消息（一次性写入完整内容）
     */
    public void completeStreamingMessage(Long messageId, String finalContent) {
        if (messageId == null) return;
        try {
            messageService.completeMessage(messageId, finalContent);
        } catch (Exception e) {
            log.error("[StateManager] Failed to complete streaming message", e);
        }
    }

    /**
     * 失败流式消息（保存部分内容并标记错误状态）
     */
    public void failStreamingMessage(Long messageId, String partialContent, String errorMessage) {
        if (messageId == null) return;
        try {
            messageService.failMessage(messageId, partialContent, errorMessage);
        } catch (Exception e) {
            log.error("[StateManager] Failed to mark streaming message as failed", e);
        }
    }


    /**
     * 加载 Agent 配置
     */
    public Agent loadAgent(String agentId) {
        try {
            Long id = Long.parseLong(agentId);
            return agentService.getAgentById(id);
        } catch (NumberFormatException e) {
            log.error("[StateManager] Invalid agent ID format: {}", agentId);
            return null;
        } catch (Exception e) {
            log.error("[StateManager] Failed to load agent: {}", agentId, e);
            return null;
        }
    }

    /**
     * 构建任务上下文
     * 从数据库查询并组装 Worker 执行所需的完整上下文
     *
     * @param sessionId 会话ID
     * @return TaskContext 对象
     */
    public TaskContext buildContext(String sessionId) {
        try {
            // 1. 加载 ExecutionPlan（获取 goal 和 currentStep）
            ExecutionPlan plan = loadPlan(sessionId);
            if (plan == null) {
                log.warn("[StateManager] No plan found for session: {}", sessionId);
                return TaskContext.builder()
                        .sessionId(sessionId)
                        .build();
            }

            // 2. 查询最近的对话历史（最多 10 条）
            List<Message> recentHistory = messageService.getRecentMessages(sessionId, 10);

            // 3. 组装 TaskContext
            TaskContext context = TaskContext.builder()
                    .sessionId(sessionId)
                    .userGoal(plan.getGoal())
                    .recentHistory(recentHistory)
                    .sharedData(new HashMap<>())  // 不再使用 artifact
                    .currentStep(plan.getCurrentStep())
                    .build();

            log.info("[StateManager] Built context: sessionId={}, historyCount={}",
                    sessionId, context.getHistoryCount());

            return context;

        } catch (Exception e) {
            log.error("[StateManager] Failed to build context for session: {}", sessionId, e);
            return TaskContext.builder()
                    .sessionId(sessionId)
                    .build();
        }
    }
}
