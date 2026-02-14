package com.agent.domain.execution.listener;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.infrastructure.event.events.TaskCompletedEvent;
import com.agent.infrastructure.event.events.TaskFailedEvent;
import com.agent.infrastructure.event.events.TaskUnblockedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 任务板事件唤醒调度器
 * 当任务状态变化时通知 Lead，触发等待中的 Lead 恢复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskBoardWakeupScheduler {

    private final InboxService inboxService;
    private final WorkerInstanceManager workerInstanceManager;

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        notifyLead(event.getSessionId(),
                String.format("Task #%d completed by %s",
                        event.getTaskId(),
                        resolveOwnerDisplayName(event.getSessionId(), event.getOwner())));
    }

    @EventListener
    public void onTaskFailed(TaskFailedEvent event) {
        notifyLead(event.getSessionId(),
                String.format("Task #%d failed (owner=%s)",
                        event.getTaskId(),
                        resolveOwnerDisplayName(event.getSessionId(), event.getOwner())));
    }

    @EventListener
    public void onTaskUnblocked(TaskUnblockedEvent event) {
        notifyLead(event.getSessionId(),
                String.format("Task #%d is unblocked by task #%d",
                        event.getTaskId(),
                        event.getUnblockedBy()));
    }

    private void notifyLead(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        String leadInstanceId = sessionId + "_lead";
        TeamMessage message = TeamMessage.builder()
                .from("taskboard")
                .to(leadInstanceId)
                .type("TASK_EVENT")
                .text(content)
                .build();

        inboxService.send(message);
        log.info("[TaskBoardWakeupScheduler] Notified lead: sessionId={}, message={}", sessionId, content);
    }

    private String resolveOwnerDisplayName(String sessionId, String ownerInstanceId) {
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            return "unknown";
        }

        WorkerInstance worker = workerInstanceManager
                .findBySessionAndInstanceId(sessionId, ownerInstanceId)
                .orElse(null);
        if (worker == null) {
            return "worker";
        }
        if (worker.getName() != null && !worker.getName().isBlank()) {
            return worker.getName();
        }
        if (worker.getWorkerId() > 0) {
            return "worker-" + worker.getWorkerId();
        }
        return "worker";
    }
}
