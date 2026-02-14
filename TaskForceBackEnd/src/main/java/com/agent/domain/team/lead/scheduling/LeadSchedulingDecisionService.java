package com.agent.domain.team.lead.scheduling;

import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.repository.WorkerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Lead 调度判定服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadSchedulingDecisionService {

    private final InboxService inboxService;
    private final TaskBoardService taskBoardService;
    private final WorkerInstanceRepository workerInstanceRepository;
    private final AgentExecutionStateService executionStateService;

    public LeadSchedulingDecision evaluate(String sessionId) {
        String leadInstanceId = sessionId + "_lead";
        boolean hasInboxMessages = safeHasInbox(leadInstanceId);

        boolean hasRunnableTasks;
        boolean hasUnfinishedTasks;
        try {
            hasRunnableTasks = !taskBoardService.getAvailableTasks(sessionId).isEmpty();
            hasUnfinishedTasks = taskBoardService.listTasks(sessionId).stream()
                    .anyMatch(task -> !task.isCompleted() && !task.isFailed());
            if (!hasUnfinishedTasks && hasWorkersInFlight(sessionId)) {
                hasUnfinishedTasks = true;
            }
        } catch (Exception e) {
            log.warn("[LeadSchedulingDecisionService] Failed to evaluate task board, keep lead running: sessionId={}",
                    sessionId, e);
            hasRunnableTasks = true;
            hasUnfinishedTasks = true;
        }

        return new LeadSchedulingDecision(hasInboxMessages, hasRunnableTasks, hasUnfinishedTasks);
    }

    private boolean safeHasInbox(String leadInstanceId) {
        try {
            return inboxService.hasNewMessages(leadInstanceId);
        } catch (Exception e) {
            log.warn("[LeadSchedulingDecisionService] Failed to check lead inbox, treat as has message: instanceId={}",
                    leadInstanceId, e);
            return true;
        }
    }

    private boolean hasWorkersInFlight(String sessionId) {
        try {
            for (WorkerInstance worker : workerInstanceRepository.findBySessionId(sessionId)) {
                if (worker == null || worker.isShutdown()) {
                    continue;
                }
                AgentExecutionStatus status = executionStateService.getStatus(worker.getInstanceId());
                if (status == AgentExecutionStatus.EXECUTING
                        || status == AgentExecutionStatus.RUNNING
                        || status == AgentExecutionStatus.WAITING_REPLY) {
                    return true;
                }
                if (worker.isWorking() || worker.isWaitingReply()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("[LeadSchedulingDecisionService] Failed to inspect worker execution, keep lead running: sessionId={}",
                    sessionId, e);
            return true;
        }
    }
}
