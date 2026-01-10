package com.agent.application.orchestration;

import com.agent.domain.model.plan.*;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.service.SessionStopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 工作流引擎
 * 核心编排组件，负责 Plan-Execute 主循环
 *
 * 特点：
 * 1. Fire-and-Forget：HTTP 线程立即返回
 * 2. 虚拟线程：后台任务使用虚拟线程执行
 * 3. 单一状态源：基于 ExecutionPlan 推导当前阶段
 * 4. 事件驱动：通过 EventBus 推送事件到前端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final StateManager stateManager;
    private final PlannerAgent plannerAgent;
    private final StepExecutor stepExecutor;
    private final ReplannerAgent replannerAgent;
    private final EventBus eventBus;
    private final SessionStopService sessionStopService;

    @Qualifier("virtualThreadExecutor")
    private final TaskExecutor virtualThreadExecutor;

    /**
     * 最大重规划次数
     */
    private static final int MAX_REPLAN_COUNT = 3;

    /**
     * 提交用户输入 - Fire and Forget
     * HTTP 线程立即返回 requestId
     */
    public String submitUserInput(String sessionId, String userText) {
        String requestId = UUID.randomUUID().toString();
        log.info("[WorkflowEngine] Submitting user input: sessionId={}, requestId={}", sessionId, requestId);

        // 清除停止标志
        sessionStopService.clearStop(sessionId);

        // 记录用户输入
        stateManager.recordUserInput(sessionId, requestId, userText);

        // 使用虚拟线程异步执行
        virtualThreadExecutor.execute(() -> processAsync(sessionId, requestId, userText));

        return requestId;
    }

    /**
     * 恢复执行（用户回答问题后）
     */
    public String resume(String sessionId, String userAnswer) {
        String requestId = UUID.randomUUID().toString();
        log.info("[WorkflowEngine] Resuming session: sessionId={}, requestId={}", sessionId, requestId);

        sessionStopService.clearStop(sessionId);
        stateManager.recordUserInput(sessionId, requestId, userAnswer);

        ExecutionPlan plan = stateManager.loadPlan(sessionId);
        if (plan != null && plan.getStatus() == PlanStatus.PAUSED) {
            plan.resume();
            stateManager.savePlan(plan);
        }

        virtualThreadExecutor.execute(() -> processAsync(sessionId, requestId, userAnswer));

        return requestId;
    }

    /**
     * 获取当前状态
     */
    public ExecutionPlan getState(String sessionId) {
        return stateManager.loadPlan(sessionId);
    }

    /**
     * 后台异步处理 - 基于 ExecutionPlan 推导状态
     */
    private void processAsync(String sessionId, String requestId, String userText) {
        log.info("[WorkflowEngine] Processing async: sessionId={}, requestId={}", sessionId, requestId);

        try {
            ExecutionPlan plan = stateManager.loadPlan(sessionId);

            // === PLANNING 阶段 ===
            if (plan == null) {
                plan = runPlanningPhase(sessionId, userText);
                if (plan == null) {
                    return; // 规划失败或需要用户输入
                }
            } else if (plan.getStatus() == PlanStatus.PAUSED && "waiting_user".equals(plan.getPauseReason())) {
                // === 用户回答了问题，根据暂停类型决定恢复策略 ===

                if (plan.needsFullReplanning()) {
                    // 策略1：Planner澄清 - 完全重新规划
                    log.info("[WorkflowEngine] Planner clarification answered, replanning from scratch");
                    stateManager.deletePlan(sessionId);
                    plan = runPlanningPhase(sessionId, userText);
                    if (plan == null) {
                        return;
                    }
                } else if (plan.needsRestepExecution()) {
                    // 策略2：Worker澄清 - 重新执行被打断的步骤
                    log.info("[WorkflowEngine] Worker clarification answered, restarting step {} (Agent: {})",
                            plan.getPausedAtStepIndex(), plan.getPausedAgentId());

                    // 重置到被打断的步骤
                    plan.resetToPausedStep();

                    // 在步骤指令中追加用户澄清内容
                    PlanStep pausedStep = plan.getPausedStep();
                    if (pausedStep != null) {
                        String originalInstruction = pausedStep.getInstruction();
                        String clarification = "\n\n【用户澄清】\n" + userText;
                        pausedStep.setInstruction(originalInstruction + clarification);
                        log.info("[WorkflowEngine] Appended user clarification to step instruction");
                    }

                    // 恢复执行状态并清除暂停上下文
                    plan.resume();
                    plan.clearPauseContext();
                    stateManager.savePlan(plan);
                } else {
                    // 其他暂停情况（USER/BLOCKED）- 直接恢复
                    plan.resume();
                    plan.clearPauseContext();
                    stateManager.savePlan(plan);
                }
            }

            // === EXECUTING 阶段 ===
            runExecutionLoop(sessionId, plan);

        } catch (Exception e) {
            log.error("[WorkflowEngine] Workflow error: sessionId={}", sessionId, e);
            eventBus.publish(sessionId, new ErrorEvent(sessionId, e.getMessage()));
        }
    }

    /**
     * 运行规划阶段
     */
    private ExecutionPlan runPlanningPhase(String sessionId, String userText) {
        log.info("[WorkflowEngine] Running planning phase: sessionId={}", sessionId);

        eventBus.publish(sessionId, new PlanningStartEvent(sessionId));

        PlannerResult result = plannerAgent.generatePlan(sessionId, userText);

        switch (result) {
            case PlannerResult.PlanGenerated pg -> {
                ExecutionPlan plan = pg.plan();
                stateManager.savePlan(plan);

                // 格式化计划内容
                String formattedPlan = formatPlanForDisplay(plan);

                // 保存 Planner 消息到数据库
                try {
                    stateManager.recordPlannerMessage(sessionId, sessionId, formattedPlan);
                } catch (Exception e) {
                    log.warn("[WorkflowEngine] Failed to save planner message", e);
                }

                // 发送事件（包含完整格式化的计划）
                eventBus.publish(sessionId, new PlanGeneratedEvent(
                        sessionId,
                        plan.getPlanId(),
                        plan.getGoal(),
                        plan.getSteps().size(),
                        formattedPlan  // 添加完整计划内容
                ));
                return plan;
            }
            case PlannerResult.NeedClarification nc -> {
                ExecutionPlan pausedPlan = ExecutionPlan.createPaused(sessionId, nc.question());
                pausedPlan.pauseForPlannerClarification(nc.question());  // 使用新方法标记来源
                stateManager.savePlan(pausedPlan);
                eventBus.publish(sessionId, new NeedClarificationEvent(
                    sessionId,
                    nc.question(),
                    "PLANNER",  // 标记来源
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
        log.info("[WorkflowEngine] Running execution loop: sessionId={}, currentStep={}",
                sessionId, plan.getCurrentStepIndex());

        while (!plan.isComplete() && !Thread.currentThread().isInterrupted()) {

            // 检查停止标志
            if (sessionStopService.shouldStop(sessionId)) {
                log.info("[WorkflowEngine] Stopped by user: sessionId={}", sessionId);
                plan.pauseForUserStop("用户停止");  // 使用新方法
                stateManager.savePlan(plan);
                eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "user_stopped"));
                return;
            }

            PlanStep currentStep = plan.getCurrentStep();
            if (currentStep == null) {
                break;
            }

            // 开始执行步骤
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

            // 执行步骤
            StepResult result = stepExecutor.execute(sessionId, currentStep);

            if (result.isSuccess()) {
                handleStepSuccess(sessionId, plan, currentStep, result);
            } else if (result.isBlocked()) {
                if (!handleStepBlocked(sessionId, plan, currentStep, result)) {
                    return; // 重规划失败，退出
                }
            } else if (result.needsUserInput()) {
                handleNeedsUserInput(sessionId, plan, currentStep, result);
                return; // 等待用户输入
            }
        }

        // 所有步骤完成
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
        step.complete(result.getOutput());

        // 判断是否是最后一步
        boolean isLastStep = (step.getStepIndex() == plan.getSteps().size() - 1);

        if (isLastStep) {
            // 最后一步完成，标记计划为完成
            log.info("[WorkflowEngine] Last step completed, marking plan as completed: sessionId={}, stepIndex={}",
                    sessionId, step.getStepIndex());
            plan.markCompleted();
        } else {
            // 不是最后一步，推进到下一步
            plan.advanceToNextStep();
            log.info("[WorkflowEngine] Advanced to next step: sessionId={}, nextStepIndex={}",
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
     * @return true 继续执行，false 退出
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

        // 尝试重规划
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

            // 继续执行新计划
            runExecutionLoop(sessionId, newPlan);
            return false; // 已经在递归中处理完毕
        } else {
            // 重规划次数已达上限
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
        // Worker澄清：记录暂停上下文
        plan.pauseForWorkerClarification(
            result.getQuestion(),
            step.getStepIndex(),
            step.getAssignedAgentId()
        );
        stateManager.savePlan(plan);

        eventBus.publish(sessionId, new NeedClarificationEvent(
            sessionId,
            result.getQuestion(),
            "WORKER",  // 标记来源
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
