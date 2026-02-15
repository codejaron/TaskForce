package com.agent.domain.team.lead.scheduling;

/**
 * Lead 调度决策快照。
 */
public record LeadSchedulingDecision(
        boolean hasInboxMessages,
        boolean hasDispatchableTasks,
        boolean hasUnfinishedTasks
) {

    public boolean shouldContinueNow() {
        return hasDispatchableTasks;
    }

    public boolean shouldWait() {
        return !shouldContinueNow() && hasUnfinishedTasks;
    }

    public boolean shouldComplete() {
        return !shouldContinueNow() && !hasUnfinishedTasks;
    }
}
