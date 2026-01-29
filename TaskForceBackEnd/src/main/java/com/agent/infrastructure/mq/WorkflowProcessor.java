package com.agent.infrastructure.mq;

import com.agent.application.orchestration.PlannerAgent;
import com.agent.application.orchestration.ReplannerAgent;
import com.agent.application.orchestration.StateManager;
import com.agent.application.orchestration.StepExecutor;
import com.agent.domain.model.plan.*;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.service.SessionStopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 工作流处理器
 * 从 WorkflowEngine 抽离出来的核心处理逻辑
 *
 * 职责：
 * 1. 幂等检查（Redis）
 * 2. 分布式锁（Redisson）
 * 3. 执行 Planner/Worker 逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowProcessor {

    private final StateManager stateManager;
    private final PlannerAgent plannerAgent;
    private final StepExecutor stepExecutor;
    private final ReplannerAgent replannerAgent;
    private final EventBus eventBus;
    private final SessionStopService sessionStopService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 幂等 key 过期时间（小时）
     */
    @Value("${app.idempotent.expire-hours:24}")
    private int idempotentExpireHours;

    /**
     * 最大重规划次数
     */
    private static final int MAX_REPLAN_COUNT = 3;

    /**
     * 幂等 key 前缀
     */
    private static final String IDEMPOTENT_KEY_PREFIX = "idempotent:request:";

    /**
     * 处理任务消息
     * 包含幂等检查 + 分布式锁 + 业务逻辑
     *
     * @param message 任务消息
     */
    public void process(TaskMessage message) {
        String sessionId = message.getSessionId();
        String requestId = message.getRequestId();
        String userText = message.getUserInput();

        // 1. 幂等检查
        if (!checkAndMarkProcessed(requestId)) {
            log.info("[Processor] Duplicate message, skip: sessionId={}, requestId={}",
                    sessionId, requestId);
            return;
        }

        // 2. 分布式锁
        String lockKey = "lock:session:" + sessionId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 等待 3s 获取锁；leaseTime=60s 兜底防死锁（Redisson Watchdog 会自动续期）
            if (!lock.tryLock(3, 60, TimeUnit.SECONDS)) {
                log.warn("[Processor] Failed to acquire lock for session: {}, skip processing (requestId={})",
                        sessionId, requestId);
                // 移除幂等标记，允许重试
                removeIdempotentMark(requestId);
                throw new RuntimeException("获取锁失败，消息将重试");
            }

            log.debug("[Processor] Acquired lock for session: {} (requestId={})", sessionId, requestId);

            try {
                log.info("[Processor] Processing message: sessionId={}, requestId={}, type={}",
                        sessionId, requestId, message.getType());

                ExecutionPlan plan = stateManager.loadPlan(sessionId);

                // === PLANNING 阶段 ===
                if (plan == null) {
                    plan = runPlanningPhase(sessionId, userText);
                    if (plan == null) {
                        return; // 规划失败或需要用户输入
                    }
                } else if (plan.getStatus() == PlanStatus.PAUSED) {
                    String pauseReason = plan.getPauseReason();

                    if ("user_stopped".equals(pauseReason)) {
                        // === 用户手动停止后恢复 ===
                        log.info("[Processor] Resuming from user stop: sessionId={}, pausedStep={}",
                                sessionId, plan.getPausedAtStepIndex());

                        plan.resetToPausedStep();
                        PlanStep pausedStep = plan.getCurrentStep();
                        if (pausedStep != null && pausedStep.getStatus() == StepStatus.IN_PROGRESS) {
                            pausedStep.resetToPending();
                        }

                        plan.resume();
                        plan.clearPauseContext();
                        stateManager.savePlan(plan);
                    } else if ("waiting_user".equals(pauseReason)) {
                        // === 用户回答了问题，根据暂停类型决定恢复策略 ===

                        if (plan.needsFullReplanning()) {
                            log.info("[Processor] Planner clarification answered, replanning from scratch");
                            stateManager.deletePlan(sessionId);
                            plan = runPlanningPhase(sessionId, userText);
                            if (plan == null) {
                                return;
                            }
                        } else if (plan.needsRestepExecution()) {
                            log.info("[Processor] Worker clarification answered, restarting step {} (Agent: {})",
                                    plan.getPausedAtStepIndex(), plan.getPausedAgentId());

                            plan.resetToPausedStep();
                            PlanStep pausedStep = plan.getPausedStep();
                            if (pausedStep != null) {
                                String originalInstruction = pausedStep.getInstruction();
                                String clarification = "\n\n【用户澄清】\n" + userText;
                                pausedStep.setInstruction(originalInstruction + clarification);
                                log.info("[Processor] Appended user clarification to step instruction");
                            }

                            plan.resume();
                            plan.clearPauseContext();
                            stateManager.savePlan(plan);
                        } else {
                            plan.resume();
                            plan.clearPauseContext();
                            stateManager.savePlan(plan);
                        }
                    }
                }

                // === EXECUTING 阶段 ===
                runExecutionLoop(sessionId, plan);

            } catch (Exception e) {
                log.error("[Processor] Workflow error: sessionId={}", sessionId, e);
                eventBus.publish(sessionId, new ErrorEvent(sessionId, e.getMessage()));
                throw e; // 抛出异常，让 RocketMQ 重试
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Processor] Interrupted while waiting for lock: sessionId={}, requestId={}",
                    sessionId, requestId);
            removeIdempotentMark(requestId);
            throw new RuntimeException("等待锁被中断", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception unlockEx) {
                    log.warn("[Processor] Failed to unlock for session: {}", sessionId, unlockEx);
                }
                log.debug("[Processor] Released lock for session: {} (requestId={})", sessionId, requestId);
            }
        }
    }

    /**
     * 幂等检查并标记为已处理
     *
     * @param requestId 请求 ID
     * @return true 表示首次处理，false 表示重复消息
     */
    private boolean checkAndMarkProcessed(String requestId) {
        String key = IDEMPOTENT_KEY_PREFIX + requestId;
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", idempotentExpireHours, TimeUnit.HOURS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 移除幂等标记（用于需要重试的场景）
     */
    private void removeIdempotentMark(String requestId) {
        String key = IDEMPOTENT_KEY_PREFIX + requestId;
        stringRedisTemplate.delete(key);
    }

    /**
     * 运行规划阶段
     */
    private ExecutionPlan runPlanningPhase(String sessionId, String userText) {
        log.info("[Processor] Running planning phase: sessionId={}", sessionId);

        eventBus.publish(sessionId, new PlanningStartEvent(sessionId));

        PlannerResult result = plannerAgent.generatePlan(sessionId, userText);

        switch (result) {
            case PlannerResult.PlanGenerated pg -> {
                ExecutionPlan plan = pg.plan();
                stateManager.savePlan(plan);

                String formattedPlan = formatPlanForDisplay(plan);

                try {
                    stateManager.recordPlannerMessage(sessionId, sessionId, formattedPlan);
                } catch (Exception e) {
                    log.warn("[Processor] Failed to save planner message", e);
                }

                eventBus.publish(sessionId, new PlanGeneratedEvent(
                        sessionId,
                        plan.getPlanId(),
                        plan.getGoal(),
                        plan.getSteps().size(),
                        formattedPlan
                ));
                return plan;
            }
            case PlannerResult.NeedClarification nc -> {
                ExecutionPlan pausedPlan = ExecutionPlan.createPaused(sessionId, nc.question());
                pausedPlan.pauseForPlannerClarification(nc.question());
                stateManager.savePlan(pausedPlan);
                eventBus.publish(sessionId, new NeedClarificationEvent(
                        sessionId,
                        nc.question(),
                        "PLANNER",
                        null,
                        null
                ));
                return null;
            }
            case PlannerResult.CannotPlan cp -> {
                eventBus.publish(sessionId, new PlanFailedEvent(sessionId, cp.reason()));
                return null;
            }
        }
    }

    /**
     * 执行循环 - 按计划顺序执行每个步骤
     */
    private void runExecutionLoop(String sessionId, ExecutionPlan plan) {
        log.info("[Processor] Running execution loop: sessionId={}, currentStep={}",
                sessionId, plan.getCurrentStepIndex());

        while (!plan.isComplete() && !Thread.currentThread().isInterrupted()) {

            if (sessionStopService.shouldStop(sessionId)) {
                log.info("[Processor] Stopped by user: sessionId={}", sessionId);
                plan.pauseForUserStop("用户停止");
                stateManager.savePlan(plan);
                eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "user_stopped"));
                return;
            }

            PlanStep currentStep = plan.getCurrentStep();
            if (currentStep == null) {
                break;
            }

            currentStep.start();
            stateManager.savePlan(plan);
            eventBus.publish(sessionId, new StepStartEvent(
                    sessionId,
                    currentStep.getStepId(),
                    currentStep.getStepIndex(),
                    currentStep.getDescription(),
                    currentStep.getAssignedAgentId(),
                    currentStep.getAssignedAgentName()
            ));

            StepResult result = stepExecutor.execute(sessionId, currentStep);

            if (sessionStopService.shouldStop(sessionId)) {
                log.info("[Processor] Stopped by user after step execution: sessionId={}, step={}",
                        sessionId, currentStep.getStepIndex());
                plan.pauseForUserStop("用户停止");
                stateManager.savePlan(plan);
                eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "user_stopped"));
                return;
            }

            if (result.isSuccess()) {
                handleStepSuccess(sessionId, plan, currentStep, result);
            } else if (result.isBlocked()) {
                if (!handleStepBlocked(sessionId, plan, currentStep, result)) {
                    return;
                }
            } else if (result.needsUserInput()) {
                handleNeedsUserInput(sessionId, plan, currentStep, result);
                return;
            }
        }

        if (plan.isComplete() || plan.getCurrentStep() == null) {
            plan.markCompleted();
            stateManager.savePlan(plan);
            eventBus.publish(sessionId, new SessionCompleteEvent(
                    sessionId,
                    "all_steps_done",
                    plan.getCompletedStepCount()
            ));
        }
    }

    /**
     * 处理步骤成功
     */
    private void handleStepSuccess(String sessionId, ExecutionPlan plan, PlanStep step, StepResult result) {
        step.complete();

        boolean isLastStep = (step.getStepIndex() == plan.getSteps().size());

        if (isLastStep) {
            log.info("[Processor] Last step completed, marking plan as completed: sessionId={}, stepIndex={}",
                    sessionId, step.getStepIndex());
            plan.markCompleted();
        } else {
            plan.advanceToNextStep();
            log.info("[Processor] Advanced to next step: sessionId={}, nextStepIndex={}",
                    sessionId, plan.getCurrentStepIndex());
        }

        stateManager.savePlan(plan);
        eventBus.publish(sessionId, new StepCompletedEvent(
                sessionId,
                step.getStepId(),
                step.getStepIndex(),
                result.getOutput()
        ));
    }

    /**
     * 处理步骤阻塞
     */
    private boolean handleStepBlocked(String sessionId, ExecutionPlan plan, PlanStep step, StepResult result) {
        step.block(result.getBlockedReason());
        stateManager.savePlan(plan);
        eventBus.publish(sessionId, new StepBlockedEvent(
                sessionId,
                step.getStepId(),
                step.getStepIndex(),
                result.getBlockedReason()
        ));

        if (plan.canReplan(MAX_REPLAN_COUNT)) {
            plan.startReplanning();
            eventBus.publish(sessionId, new ReplanningStartEvent(sessionId, result.getBlockedReason()));

            ExecutionPlan newPlan = replannerAgent.replan(sessionId, plan, result.getBlockedReason());
            stateManager.savePlan(newPlan);

            if (newPlan.getStatus() == PlanStatus.FAILED) {
                eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "replan_failed"));
                return false;
            }

            eventBus.publish(sessionId, new PlanUpdatedEvent(
                    sessionId,
                    newPlan.getPlanId(),
                    newPlan.getSteps().size(),
                    newPlan.getReplanCount()
            ));

            runExecutionLoop(sessionId, newPlan);
            return false;
        } else {
            plan.pauseForUserInput("重规划次数已达上限，请提供新的指示");
            stateManager.savePlan(plan);
            eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "replan_limit_reached"));
            return false;
        }
    }

    /**
     * 处理需要用户输入
     */
    private void handleNeedsUserInput(String sessionId, ExecutionPlan plan, PlanStep step, StepResult result) {
        plan.pauseForWorkerClarification(
                result.getQuestion(),
                step.getStepIndex(),
                step.getAssignedAgentId()
        );
        stateManager.savePlan(plan);

        eventBus.publish(sessionId, new NeedClarificationEvent(
                sessionId,
                result.getQuestion(),
                "WORKER",
                step.getStepId(),
                step.getAssignedAgentId()
        ));
    }

    /**
     * 格式化计划内容用于显示和存储
     */
    private String formatPlanForDisplay(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 执行计划\n");
        sb.append("目标: ").append(plan.getGoal()).append("\n\n");
        for (PlanStep step : plan.getSteps()) {
            sb.append(String.format("步骤 %d: %s\n", step.getStepIndex(), step.getDescription()));
            sb.append(String.format("  负责: %s\n", step.getAssignedAgentName()));
            sb.append(String.format("  指令: %s\n", step.getInstruction()));
            sb.append("\n");
        }
        return sb.toString();
    }
}

