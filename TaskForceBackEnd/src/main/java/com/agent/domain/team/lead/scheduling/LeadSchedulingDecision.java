package com.agent.domain.team.lead.scheduling;

/**
 * Lead 调度决策快照。
 */
public record LeadSchedulingDecision(
        boolean hasInboxMessages,
        boolean hasRunnableTasks,
        boolean hasUnfinishedTasks
) {

    public boolean shouldContinueNow() {
        return hasInboxMessages || hasRunnableTasks;
    }

    public boolean shouldWait() {
        return !shouldContinueNow() && hasUnfinishedTasks;
    }

    public boolean shouldComplete() {
        return !shouldContinueNow() && !hasUnfinishedTasks;
    }
}
