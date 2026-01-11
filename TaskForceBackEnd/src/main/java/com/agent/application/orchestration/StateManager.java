package com.agent.application.orchestration;

import com.agent.domain.model.context.TaskContext;
import com.agent.domain.model.plan.ExecutionPlan;
import com.agent.domain.model.plan.PlanStep;
import com.agent.domain.repository.PlanRepository;
import com.agent.entity.Message;
import com.agent.entity.SessionArtifact;
import com.agent.mapper.MessageMapper;
import com.agent.mapper.SessionArtifactMapper;
import com.agent.model.AgentProfile;
import com.agent.service.AgentProfileService;
import com.agent.service.MessageService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

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
    private final AgentProfileService agentProfileService;
    private final SessionArtifactMapper sessionArtifactMapper;
    private final MessageService messageService;

    // === Plan 操作 ===

    /**
     * 加载计划
     */
    public ExecutionPlan loadPlan(String sessionId) {
        return planRepository.findBySessionId(sessionId).orElse(null);
    }

    /**
     * 保存计划
     */
    public void savePlan(ExecutionPlan plan) {
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);
    }

    /**
     * 删除计划
     */
    public void deletePlan(String sessionId) {
        planRepository.deleteBySessionId(sessionId);
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
            messageMapper.insert(msg);
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
            messageMapper.insert(msg);
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
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("[StateManager] Failed to record step message", e);
            return null;
        }
    }


    /**
     * 加载 Agent 配置
     */
    public AgentProfile loadAgent(String agentId) {
        try {
            return agentProfileService.findById(agentId).orElse(null);
        } catch (Exception e) {
            log.error("[StateManager] Failed to load agent: {}", agentId, e);
            return null;
        }
    }

    // === Artifact 操作 ===

    /**
     * 保存或更新 Artifact (Upsert)
     * 使用 MyBatis-Plus 查询 + 更新/插入的方式实现 Upsert 语义
     *
     * @param sessionId 会话ID
     * @param key Artifact键名
     * @param value Artifact值
     */
    public void saveArtifact(String sessionId, String key, String value) {
        try {
            // 查询是否已存在
            QueryWrapper<SessionArtifact> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("session_id", sessionId)
                        .eq("artifact_key", key);

            SessionArtifact existing = sessionArtifactMapper.selectOne(queryWrapper);

            if (existing != null) {
                // 更新
                existing.setArtifactValue(value);
                existing.setUpdatedAt(LocalDateTime.now());
                sessionArtifactMapper.updateById(existing);
                log.debug("[StateManager] Updated artifact: sessionId={}, key={}, valueLength={}",
                         sessionId, key, value.length());
            } else {
                // 新建
                SessionArtifact artifact = new SessionArtifact();
                artifact.setSessionId(sessionId);
                artifact.setArtifactKey(key);
                artifact.setArtifactValue(value);
                artifact.setCreatedAt(LocalDateTime.now());
                artifact.setUpdatedAt(LocalDateTime.now());
                sessionArtifactMapper.insert(artifact);
                log.debug("[StateManager] Created artifact: sessionId={}, key={}, valueLength={}",
                         sessionId, key, value.length());
            }
        } catch (Exception e) {
            log.error("[StateManager] Failed to save artifact: sessionId={}, key={}", sessionId, key, e);
            // 不抛出异常，避免中断主流程
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

            // 3. 查询所有 Artifact（黑板数据）
            Map<String, String> sharedData = queryAllArtifacts(sessionId);

            // 4. 组装 TaskContext
            TaskContext context = TaskContext.builder()
                    .sessionId(sessionId)
                    .userGoal(plan.getGoal())
                    .recentHistory(recentHistory)
                    .sharedData(sharedData)
                    .currentStep(plan.getCurrentStep())
                    .build();

            log.info("[StateManager] Built context: sessionId={}, historyCount={}, artifactCount={}",
                    sessionId, context.getHistoryCount(), context.getSharedDataCount());

            return context;

        } catch (Exception e) {
            log.error("[StateManager] Failed to build context for session: {}", sessionId, e);
            return TaskContext.builder()
                    .sessionId(sessionId)
                    .build();
        }
    }


    /**
     * 查询所有 Artifact
     *
     * @param sessionId 会话ID
     * @return Artifact Map（key -> value）
     */
    private Map<String, String> queryAllArtifacts(String sessionId) {
        try {
            QueryWrapper<SessionArtifact> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("session_id", sessionId)
                        .orderByDesc("updated_at");  // 最新的在前

            List<SessionArtifact> artifacts = sessionArtifactMapper.selectList(queryWrapper);

            Map<String, String> result = new LinkedHashMap<>();
            for (SessionArtifact artifact : artifacts) {
                result.put(artifact.getArtifactKey(), artifact.getArtifactValue());
            }
            return result;
        } catch (Exception e) {
            log.error("[StateManager] Failed to query artifacts", e);
            return new HashMap<>();
        }
    }
}
