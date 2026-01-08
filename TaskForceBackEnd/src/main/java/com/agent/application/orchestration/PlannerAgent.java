package com.agent.application.orchestration;

import com.agent.domain.model.plan.*;
import com.agent.entity.Agent;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.mapper.AgentMapper;
import com.agent.model.AgentProfile;
import com.agent.service.AgentProfileService;
import com.agent.service.SessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Planner Agent
 * 负责生成执行计划或询问用户澄清
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerAgent {

    private final LlmAdapter llmAdapter;
    private final AgentProfileService agentProfileService;
    private final SessionService sessionService;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final AgentMapper agentMapper;
    private final PromptManager promptManager;

    /**
     * 生成计划
     */
    public PlannerResult generatePlan(String sessionId, String userGoal) {
        log.info("[PlannerAgent] Generating plan for session: {}", sessionId);

        // 获取可用 Worker 列表
        List<AgentProfile> workers = loadWorkers(sessionId);

        String prompt = promptManager.buildPlannerPrompt(workers, userGoal);
        log.debug("[PlannerAgent] 📝 发送给 LLM 的完整 Prompt:\n{}", prompt);

        StringBuilder response = new StringBuilder();

        try {
            // 流式调用 LLM（只累积响应，不发送 delta 事件）
            llmAdapter.streamChat(getPlannerAgentId(), sessionId, prompt, null)
                    .doOnNext(response::append)
                    .blockLast();

            return parseResult(sessionId, response.toString(), workers);

        } catch (Exception e) {
            log.error("[PlannerAgent] Failed to generate plan", e);
            return new PlannerResult.CannotPlan("规划失败: " + e.getMessage());
        }
    }

    /**
     * 解析 Planner 响应
     */
    private PlannerResult parseResult(String sessionId, String response, List<AgentProfile> workers) {
        log.info("[PlannerAgent] Raw response length: {}", response != null ? response.length() : 0);
        log.debug("[PlannerAgent] Raw response: {}", response);

        if (response == null || response.trim().isEmpty()) {
            log.warn("[PlannerAgent] Empty response from LLM");
            return new PlannerResult.CannotPlan("LLM 返回空响应");
        }

        try {
            String json = extractJson(response);
            log.debug("[PlannerAgent] 📄 提取的完整 JSON:\n{}", json);

            JsonNode node = objectMapper.readTree(json);
            String type = node.path("type").asText();
            log.info("[PlannerAgent] Parsed type: '{}'", type);

            return switch (type) {
                case "plan" -> {
                    ExecutionPlan plan = parsePlan(sessionId, node, workers);
                    yield new PlannerResult.PlanGenerated(plan);
                }
                case "question" -> {
                    String question = node.path("content").asText();
                    yield new PlannerResult.NeedClarification(question);
                }
                case "cannot_plan" -> {
                    String reason = node.path("reason").asText();
                    yield new PlannerResult.CannotPlan(reason);
                }
                default -> {
                    log.warn("[PlannerAgent] Unknown type '{}', raw response: {}", type,
                            response.length() > 500 ? response.substring(0, 500) : response);
                    yield new PlannerResult.CannotPlan("Unknown response type: " + type);
                }
            };
        } catch (Exception e) {
            log.error("[PlannerAgent] Failed to parse response: {}",
                    response.length() > 500 ? response.substring(0, 500) : response, e);
            return new PlannerResult.CannotPlan("解析响应失败: " + e.getMessage());
        }
    }

    /**
     * 解析计划
     */
    private ExecutionPlan parsePlan(String sessionId, JsonNode node, List<AgentProfile> workers) {
        String goal = node.path("goal").asText();
        JsonNode stepsNode = node.path("steps");

        List<PlanStep> steps = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            String agentId = stepNode.path("assignedAgentId").asText();
            AgentProfile agent = findAgentById(workers, agentId);

            PlanStep step = PlanStep.builder()
                    .stepId(UUID.randomUUID().toString())
                    .stepIndex(stepNode.path("stepIndex").asInt())
                    .description(stepNode.path("description").asText())
                    .assignedAgentId(agentId)
                    .assignedAgentName(agent != null ? agent.getName() : "Unknown")
                    .requiredCapability(stepNode.path("requiredCapability").asText())
                    .instruction(stepNode.path("instruction").asText())
                    .expectedOutput(stepNode.path("expectedOutput").asText())
                    .status(StepStatus.PENDING)
                    .build();
            steps.add(step);
        }

        return ExecutionPlan.builder()
                .planId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .goal(goal)
                .steps(steps)
                .currentStepIndex(0)
                .status(PlanStatus.EXECUTING)
                .build();
    }

    /**
     * 提取 JSON
     */
    private String extractJson(String response) {
        // 尝试提取 ```json ... ``` 块
        int start = response.indexOf("```json");
        if (start >= 0) {
            start += 7;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }

        // 尝试提取 { ... } 块
        int braceStart = response.indexOf("{");
        int braceEnd = response.lastIndexOf("}");
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }

        return response;
    }

    /**
     * 加载可用 Worker
     */
    private List<AgentProfile> loadWorkers(String sessionId) {
        try {
            // 从会话中获取关联的 Agent 列表
            var sessionAgents = sessionService.getSessionAgents(sessionId);
            if (sessionAgents.isEmpty()) {
                // 如果会话没有关联 Agent，返回所有可用的 Agent
                return agentProfileService.listAll();
            }

            // 根据 AgentId 加载对应的 AgentProfile
            return sessionAgents.stream()
                    .map(sa -> agentProfileService.findById(String.valueOf(sa.getAgentId())))
                    .filter(opt -> opt.isPresent())
                    .map(opt -> opt.get())
                    .toList();
        } catch (Exception e) {
            log.error("[PlannerAgent] Failed to load workers", e);
            return List.of();
        }
    }

    /**
     * 根据 ID 查找 Agent
     */
    private AgentProfile findAgentById(List<AgentProfile> workers, String agentId) {
        return workers.stream()
                .filter(w -> agentId.equals(w.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取 Planner Agent ID
     * 从数据库中查找 role_type='PLANNER' 的 Agent
     */
    private Long getPlannerAgentId() {
        try {
            Agent plannerAgent = agentMapper.selectOne(
                    new LambdaQueryWrapper<Agent>()
                            .eq(Agent::getRoleType, "PLANNER")
                            .last("LIMIT 1")
            );

            if (plannerAgent != null) {
                log.debug("[PlannerAgent] Found PLANNER agent: id={}, name={}",
                        plannerAgent.getId(), plannerAgent.getName());
                return plannerAgent.getId();
            }


            return null;

        } catch (Exception e) {
            log.error("[PlannerAgent] Failed to get planner agent ID, using default", e);
            return 1L;
        }
    }
}
