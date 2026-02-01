package com.agent.dto;

import com.agent.domain.model.plan.ExecutionPlan;
import com.agent.domain.model.plan.PlanStatus;

import java.util.List;

/**
 * 工作流状态响应
 */
public record WorkflowStateResponse(
        String planId,
        String sessionId,
        String goal,
        String status,
        String currentPhase,
        int currentStepIndex,
        int totalSteps,
        int completedSteps,
        String pauseReason,
        String pendingQuestion,
        List<StepSummary> steps
) {
    public record StepSummary(
            String stepId,
            int stepIndex,
            String instruction,
            String status,
            String assignedAgentName
    ) {}

    public static WorkflowStateResponse from(ExecutionPlan plan) {
        if (plan == null) {
            return null;
        }

        List<StepSummary> stepSummaries = plan.getSteps() == null ? List.of() :
                plan.getSteps().stream()
                        .map(step -> new StepSummary(
                                step.getStepId(),
                                step.getStepIndex(),
                                step.getInstruction(),
                                step.getStatus().name(),
                                step.getAssignedAgentName()
                        ))
                        .toList();

        return new WorkflowStateResponse(
                plan.getPlanId(),
                plan.getSessionId(),
                plan.getGoal(),
                plan.getStatus().name(),
                plan.getCurrentPhase(),
                plan.getCurrentStepIndex(),
                plan.getSteps() != null ? plan.getSteps().size() : 0,
                plan.getCompletedStepCount(),
                plan.getPauseReason(),
                plan.getPendingQuestion(),
                stepSummaries
        );
    }
}
