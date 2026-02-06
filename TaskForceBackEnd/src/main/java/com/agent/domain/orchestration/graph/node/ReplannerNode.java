package com.agent.domain.orchestration.graph.node;

import com.agent.domain.orchestration.dto.PlanStepDTO;
import com.agent.domain.orchestration.dto.ReplanResponseDTO;
import com.agent.domain.orchestration.model.*;
import com.agent.domain.orchestration.state.StateManager;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.service.AgentService;
import com.agent.service.SessionService;
import com.agent.service.SessionStopService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReplannerNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final LlmAdapter llmAdapter;
    private final PromptManager promptManager;
    private final AgentService agentService;
    private final SessionService sessionService;
    private final SessionStopService sessionStopService;

    private static final int MAX_REPLAN_ATTEMPTS = 1;

    private final BeanOutputConverter<ReplanResponseDTO> replanOutputConverter =
            new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        int stepIndex = state.value("currentStepIndex", 0);

        log.info("[ReplannerNode] Starting: sessionId={}, blockedStep={}", sessionId, stepIndex);

        if (sessionStopService.shouldStop(sessionId)) {
            eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "USER_STOP"));
            return Map.of("nextAction", "complete");
        }

        ExecutionPlan plan = stateManager.loadPlan(sessionId);
        if (plan == null) {
            log.error("[ReplannerNode] No plan found: sessionId={}", sessionId);
            return Map.of("nextAction", "complete");
        }

        // 已经重规划过一次还是失败 → 暂停等用户
        if (plan.getReplanCount() >= MAX_REPLAN_ATTEMPTS) {
            PlanStep blockedStep = plan.getSteps().get(stepIndex);
            String question = String.format(
                    "步骤 %d「%s」重新规划后仍然失败（原因：%s）。\n请问您希望如何处理？\n1. 提供额外信息帮助完成\n2. 跳过该步骤继续执行后续任务\n3. 终止当前任务",
                    stepIndex + 1,
                    blockedStep.getInstruction(),
                    blockedStep.getBlockedReason() != null ? blockedStep.getBlockedReason() : "未知");

            plan.pauseForWorkerClarification(question, stepIndex, blockedStep.getAssignedAgentId());
            stateManager.savePlan(plan);
            eventBus.publish(sessionId, new NeedClarificationEvent(sessionId, question));

            Map<String, Object> result = new HashMap<>();
            result.put("nextAction", "clarify");
            result.put("clarifyQuestion", question);
            result.put("currentStepIndex", stepIndex);
            return result;
        }

        // 第一次重规划：调 LLM
        eventBus.publish(sessionId, new ReplanningStartEvent(sessionId, "步骤执行失败，正在尝试换一种方式"));

        List<Agent> workers = loadWorkers(sessionId);
        PlanStep blockedStep = plan.getSteps().get(stepIndex);

        String formatInstructions = replanOutputConverter.getFormat();
        String workersInfo = formatWorkers(workers);
        String prompt = promptManager.buildReplannerPrompt(
                plan.getGoal(),
                plan.getCompletedStepCount(),
                plan.getSteps().size(),
                stepIndex,
                blockedStep.getInstruction(),
                blockedStep.getBlockedReason() != null ? blockedStep.getBlockedReason() : "执行失败",
                workersInfo,
                formatInstructions);

        log.debug("[ReplannerNode] Prompt:\n{}", prompt);

        try {
            ReplanResponseDTO dto = callLlmWithRetry(sessionId, prompt, 2);
            return applyReplan(sessionId, plan, dto, workers, stepIndex);
        } catch (Exception e) {
            log.error("[ReplannerNode] LLM call failed: sessionId={}", sessionId, e);
            plan.incrementReplanCount();
            stateManager.savePlan(plan);
            eventBus.publish(sessionId, new PlanFailedEvent(sessionId, "重规划失败: " + e.getMessage()));
            return Map.of("nextAction", "complete");
        }
    }

    private ReplanResponseDTO callLlmWithRetry(String sessionId, String prompt, int maxRetries) throws Exception {
        String currentPrompt = prompt;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            StringBuilder response = new StringBuilder();
            try {
                Long plannerAgentId = getPlannerAgentId();

                llmAdapter.streamChat(plannerAgentId, sessionId, null, currentPrompt, null)
                        .doOnNext(token -> {
                            response.append(token);
                            eventBus.publish(sessionId, new ReplannerDeltaEvent(sessionId, token));
                        })
                        .blockLast();

                return replanOutputConverter.convert(response.toString());

            } catch (Exception e) {
                lastException = e;
                log.warn("[ReplannerNode] Parse failed attempt {}: {}", attempt, e.getMessage());
                if (attempt < maxRetries) {
                    currentPrompt = String.format("""
                            你刚才生成的 JSON 解析失败。错误：%s
                            原始响应：%s
                            请只输出修正后的纯 JSON。
                            """, e.getMessage(),
                            response.substring(0, Math.min(response.length(), 500)));
                }
            }
        }
        throw new RuntimeException("Replan parse failed after " + maxRetries + " attempts", lastException);
    }

    private Map<String, Object> applyReplan(String sessionId, ExecutionPlan plan,
                                            ReplanResponseDTO dto, List<Agent> workers, int blockedStepIndex) {

        if (dto.getSteps() == null || dto.getSteps().isEmpty()) {
            // LLM 认为可以跳过
            log.info("[ReplannerNode] LLM says skip step {}", blockedStepIndex);
            plan.getSteps().get(blockedStepIndex).setStatus(StepStatus.DONE);
            plan.incrementReplanCount();
            stateManager.savePlan(plan);

            int nextIndex = blockedStepIndex + 1;
            if (nextIndex >= plan.getSteps().size()) {
                plan.markCompleted();
                stateManager.savePlan(plan);
                eventBus.publish(sessionId, new SessionCompleteEvent(sessionId,
                        "所有步骤已完成（跳过了部分失败步骤）", plan.getSteps().size()));
                return Map.of("nextAction", "complete");
            }
            return Map.of("nextAction", "continue", "currentStepIndex", nextIndex);
        }

        // 保留已完成步骤 + 替换从 blockedStep 开始的部分
        List<PlanStep> newSteps = new ArrayList<>();
        for (int i = 0; i < blockedStepIndex; i++) {
            newSteps.add(plan.getSteps().get(i));
        }

        int stepIndexCounter = blockedStepIndex + 1;
        for (PlanStepDTO stepDto : dto.getSteps()) {
            Agent agent = findAgentById(workers, stepDto.getAssignedAgentId());
            newSteps.add(PlanStep.builder()
                    .stepId(UUID.randomUUID().toString())
                    .stepIndex(stepIndexCounter++)
                    .assignedAgentId(stepDto.getAssignedAgentId())
                    .assignedAgentName(agent != null ? agent.getName() : "Unknown")
                    .instruction(stepDto.getInstruction())
                    .expectedOutput(stepDto.getExpectedOutput())
                    .status(StepStatus.PENDING)
                    .build());
        }

        plan.updateSteps(newSteps);
        plan.setCurrentStepIndex(blockedStepIndex);
        stateManager.savePlan(plan);

        eventBus.publish(sessionId, new PlanUpdatedEvent(
                sessionId, plan.getPlanId(), newSteps.size(), plan.getReplanCount()));

        log.info("[ReplannerNode] Replanned: newSteps={}, resumeAt={}", newSteps.size(), blockedStepIndex);
        return Map.of("nextAction", "continue", "currentStepIndex", blockedStepIndex);
    }

    private Agent findAgentById(List<Agent> workers, String agentId) {
        return workers.stream()
                .filter(w -> String.valueOf(w.getId()).equals(agentId))
                .findFirst().orElse(null);
    }

    private String formatWorkers(List<Agent> workers) {
        return workers.stream()
                .map(w -> String.format("- ID: %s, 名称: %s, 描述: %s",
                        w.getId(), w.getName(),
                        w.getDescription() != null ? w.getDescription() : "通用任务执行"))
                .collect(Collectors.joining("\n"));
    }

    private List<Agent> loadWorkers(String sessionId) {
        try {
            var sessionAgents = sessionService.getSessionAgents(sessionId);
            if (sessionAgents.isEmpty()) return agentService.getAllAgents();
            return sessionAgents.stream()
                    .map(sa -> { try { return agentService.getAgentById(sa.getAgentId()); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Long getPlannerAgentId() {
        try {
            return agentService.getAllAgentsIncludeSystem().stream()
                    .filter(a -> "PLANNER".equals(a.getRoleType()))
                    .findFirst().map(Agent::getId).orElse(null);
        } catch (Exception e) { return null; }
    }
}
