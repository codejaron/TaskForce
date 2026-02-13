package com.agent.domain.execution.listener;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
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

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        notifyLead(event.getSessionId(),
                String.format("Task #%d completed by %s",
                        event.getTaskId(),
                        event.getOwner() == null || event.getOwner().isBlank() ? "unknown" : event.getOwner()));
    }

    @EventListener
    public void onTaskFailed(TaskFailedEvent event) {
        notifyLead(event.getSessionId(),
                String.format("Task #%d failed (owner=%s)",
                        event.getTaskId(),
                        event.getOwner() == null || event.getOwner().isBlank() ? "unknown" : event.getOwner()));
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
}
