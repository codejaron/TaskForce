package com.agent.domain.orchestration.state;

import com.agent.domain.orchestration.model.TaskContext;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.repository.PlanRepository;
import com.agent.infrastructure.persistence.entity.Message;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.mapper.MessageMapper;
import com.agent.service.AgentService;
import com.agent.service.MessageService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 状态管理器
 * 负责 ExecutionPlan 的加载/保存和事件记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateManager {

    private final PlanRepository planRepository;
    private final MessageMapper messageMapper;
    private final AgentService agentService;
    private final MessageService messageService;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final long PLAN_CACHE_TTL_MINUTES = 30;

    private static String planCacheKey(String sessionId) {
        return "plan:" + sessionId;
    }

    // === Plan 操作 ===

    /**
     * 加载计划（Cache-Aside）
     */
    public ExecutionPlan loadPlan(String sessionId) {
        String cacheKey = planCacheKey(sessionId);

        // 1) Redis 优先
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, ExecutionPlan.class);
            }
        } catch (Exception e) {
            // 缓存异常不能影响主流程
            log.warn("[StateManager] Redis deserialize failed, fallback to DB: sessionId={}", sessionId, e);
        }

        // 2) DB fallback
        ExecutionPlan plan = planRepository.findBySessionId(sessionId).orElse(null);

        // 3) 回填缓存
        if (plan != null) {
            cachePlan(sessionId, plan);
        }
        return plan;
    }

    /**
     * 保存计划（更新DB + 删除缓存）
     */
    public void savePlan(ExecutionPlan plan) {
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);

        // 删除 Redis 缓存，让下次读取时从 DB 加载最新数据
        try {
            redisTemplate.delete(planCacheKey(plan.getSessionId()));
        } catch (Exception e) {
            log.warn("[StateManager] Failed to evict plan cache: sessionId={}", plan.getSessionId(), e);
        }
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

    private void cachePlan(String sessionId, ExecutionPlan plan) {
        try {
            String json = objectMapper.writeValueAsString(plan);
            redisTemplate.opsForValue().set(
                    planCacheKey(sessionId),
                    json,
                    PLAN_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (Exception e) {
            log.warn("[StateManager] Failed to cache plan: sessionId={}", sessionId, e);
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
     * 追加流式内容（定期调用）
     */
    public void appendStreamingContent(Long messageId, String delta) {
        if (messageId == null) return;
        try {
            messageService.appendContent(messageId, delta);
        } catch (Exception e) {
            log.error("[StateManager] Failed to append streaming content", e);
        }
    }

    /**
     * 完成流式消息
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
