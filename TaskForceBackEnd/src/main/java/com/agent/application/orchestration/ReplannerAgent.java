package com.agent.application.orchestration;

import com.agent.domain.model.plan.*;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Replanner Agent
 * 负责在步骤阻塞时重新规划
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplannerAgent {

    private final LlmAdapter llmAdapter;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final PromptManager promptManager;

    /**
     * 重规划
     */
    public ExecutionPlan replan(String sessionId, ExecutionPlan currentPlan, String blockedReason) {
        log.info("[ReplannerAgent] Replanning for session: {}, replanCount: {}",
                sessionId, currentPlan.getReplanCount());

        PlanStep blockedStep = currentPlan.getCurrentStep();

        String prompt = promptManager.buildReplannerPrompt(
                currentPlan.getGoal(),
                currentPlan.getCompletedStepCount(),
                currentPlan.getSteps().size(),
                blockedStep != null ? blockedStep.getStepIndex() : 0,
                blockedStep != null ? blockedStep.getDescription() : "Unknown",
                blockedReason
        );
        log.debug("[ReplannerAgent] 🔄 重规划 Prompt:\n{}", prompt);

        StringBuilder response = new StringBuilder();

        try {
            llmAdapter.streamChat(getPlannerAgentId(), sessionId, prompt, null)
                    .doOnNext(token -> {
                        response.append(token);
                        eventBus.publish(sessionId, new ReplannerDeltaEvent(sessionId, token));
                    })
                    .blockLast();

            String fullResponse = response.toString();
            log.debug("[ReplannerAgent] 📥 LLM 完整响应:\n{}", fullResponse);

            // 解析新计划
            ExecutionPlan newPlan = parseNewPlan(sessionId, currentPlan, fullResponse);
            return newPlan;

        } catch (Exception e) {
            log.error("[ReplannerAgent] Replan failed", e);
            // 返回原计划，标记为失败
            currentPlan.markFailed("重规划失败: " + e.getMessage());
            return currentPlan;
        }
    }

    /**
     * 解析新计划
     */
    private ExecutionPlan parseNewPlan(String sessionId, ExecutionPlan currentPlan, String response) {
        try {
            String json = extractJson(response);
            JsonNode node = objectMapper.readTree(json);

            String goal = node.has("goal") ? node.path("goal").asText() : currentPlan.getGoal();
            JsonNode stepsNode = node.path("steps");

            List<PlanStep> newSteps = new ArrayList<>();
            for (JsonNode stepNode : stepsNode) {
                PlanStep step = PlanStep.builder()
                        .stepId(UUID.randomUUID().toString())
                        .stepIndex(stepNode.path("stepIndex").asInt())
                        .description(stepNode.path("description").asText())
                        .assignedAgentId(stepNode.path("assignedAgentId").asText())
                        .instruction(stepNode.path("instruction").asText())
                        .expectedOutput(stepNode.path("expectedOutput").asText())
                        .status(StepStatus.PENDING)
                        .build();
                newSteps.add(step);
            }

            // 创建新计划
            return ExecutionPlan.builder()
                    .planId(UUID.randomUUID().toString())
                    .sessionId(sessionId)
                    .goal(goal)
                    .steps(newSteps)
                    .currentStepIndex(0)
                    .status(PlanStatus.EXECUTING)
                    .replanCount(currentPlan.getReplanCount() + 1)
                    .build();

        } catch (Exception e) {
            log.error("[ReplannerAgent] Failed to parse new plan", e);
            currentPlan.incrementReplanCount();
            currentPlan.markFailed("解析新计划失败");
            return currentPlan;
        }
    }

    /**
     * 提取 JSON
     */
    private String extractJson(String response) {
        int start = response.indexOf("```json");
        if (start >= 0) {
            start += 7;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }

        int braceStart = response.indexOf("{");
        int braceEnd = response.lastIndexOf("}");
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }

        return response;
    }

    private Long getPlannerAgentId() {
        return 1L;
    }
}
