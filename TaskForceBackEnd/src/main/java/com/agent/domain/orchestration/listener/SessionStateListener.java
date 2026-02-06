package com.agent.domain.orchestration.listener;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.OrchestrationEvent;
import com.agent.infrastructure.event.events.*;
import com.agent.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 会话状态监听器
 * 通过 Spring 事件机制自动根据事件更新会话状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStateListener {

    private final SessionService sessionService;

    /**
     * 监听规划开始事件 -> RUNNING
     */
    @EventListener
    public void onPlanningStart(PlanningStartEvent event) {
        updateSessionStatus(event.getSessionId(), "RUNNING");
    }

    /**
     * 监听计划生成事件 -> RUNNING
     */
    @EventListener
    public void onPlanGenerated(PlanGeneratedEvent event) {
        updateSessionStatus(event.getSessionId(), "RUNNING");
    }

    /**
     * 监听步骤开始事件 -> RUNNING
     */
    @EventListener
    public void onStepStart(StepStartEvent event) {
        updateSessionStatus(event.getSessionId(), "RUNNING");
    }

    /**
     * 监听会话完成事件 -> COMPLETED
     */
    @EventListener
    public void onSessionComplete(SessionCompleteEvent event) {
        updateSessionStatus(event.getSessionId(), "COMPLETED");
    }

    /**
     * 监听会话暂停事件 -> PAUSED
     */
    @EventListener
    public void onSessionPause(SessionPauseEvent event) {
        updateSessionStatus(event.getSessionId(), "PAUSED");
    }

    /**
     * 监听会话恢复事件 -> RUNNING
     */
    @EventListener
    public void onSessionResume(SessionResumeEvent event) {
        updateSessionStatus(event.getSessionId(), "RUNNING");
    }

    /**
     * 监听计划失败事件 -> FAILED
     */
    @EventListener
    public void onPlanFailed(PlanFailedEvent event) {
        updateSessionStatus(event.getSessionId(), "FAILED");
    }

    /**
     * 监听错误事件 -> FAILED
     */
    @EventListener
    public void onError(ErrorEvent event) {
        updateSessionStatus(event.getSessionId(), "FAILED");
    }

    /**
     * 更新会话状态（带去重检查）
     */
    private void updateSessionStatus(String sessionId, String newStatus) {
        try {
            var session = sessionService.getSessionById(sessionId);
            String currentStatus = session.getStatus();

            // 避免重复更新相同状态
            if (newStatus.equals(currentStatus)) {
                log.debug("[SessionStateListener] Status unchanged: sessionId={}, status={}", 
                        sessionId, currentStatus);
                return;
            }

            // 状态转换规则检查
            if (!isValidTransition(currentStatus, newStatus)) {
                log.warn("[SessionStateListener] Invalid state transition: sessionId={}, from={}, to={}", 
                        sessionId, currentStatus, newStatus);
                return;
            }

            sessionService.updateSessionStatus(sessionId, newStatus);
            log.info("[SessionStateListener] Status updated: sessionId={}, {} -> {}", 
                    sessionId, currentStatus, newStatus);

        } catch (Exception e) {
            log.error("[SessionStateListener] Failed to update session status: sessionId={}, newStatus={}", 
                    sessionId, newStatus, e);
        }
    }

    /**
     * 检查状态转换是否合法
     */
    private boolean isValidTransition(String from, String to) {
        // COMPLETED 和 FAILED 是终态，不能再转换
        if ("COMPLETED".equals(from) || "FAILED".equals(from)) {
            return false;
        }

        // 其他状态转换都允许
        return true;
    }
}
