package com.agent.domain.execution.listener;

import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.infrastructure.event.events.InboxMessageEvent;
import com.agent.service.TeamOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboxWakeupScheduler {

    private final WorkerInstanceManager workerInstanceManager;
    private final TeamOrchestrationService teamOrchestrationService;

    @EventListener
    public void onInboxMessage(InboxMessageEvent event) {
        String target = event.getTo();
        if (target == null || target.isBlank()) {
            return;
        }

        boolean resumed;
        if (target.endsWith("_lead")) {
            resumed = teamOrchestrationService.resumeLeadIfWaiting(event.getSessionId());
        } else {
            resumed = workerInstanceManager.resumeIfWaitingReply(target);
        }

        if (resumed) {
            log.info("[InboxWakeupScheduler] Resumed waiting instance: sessionId={}, target={}",
                    event.getSessionId(), target);
        }
    }
}
