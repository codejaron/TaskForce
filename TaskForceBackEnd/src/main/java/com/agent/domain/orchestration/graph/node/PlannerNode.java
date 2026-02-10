package com.agent.domain.orchestration.graph.node;

import com.agent.domain.orchestration.state.StateManager;
import com.agent.domain.orchestration.dto.PlannerResponseDTO;
import com.agent.domain.orchestration.model.TaskContext;
import com.agent.domain.orchestration.model.*;
import com.agent.domain.orchestration.validator.DAGValidator;
import com.agent.common.exception.SessionStoppedException;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.service.AgentService;
import com.agent.service.SessionService;
import com.agent.service.SessionStopService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Planner Node
 * 负责生成执行计划或询问用户澄清
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final LlmAdapter llmAdapter;
    private final PromptManager promptManager;
    private final AgentService agentService;
    private final SessionService sessionService;
    private final AgentMapper agentMapper;
    private final SessionStopService sessionStopService;
    
    private final BeanOutputConverter<PlannerResponseDTO> plannerOutputConverter =
            new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        String requestId = state.value("requestId", "");
        String userInput = state.value("userInput", "");
        
        log.info("[PlannerNode] Starting: sessionId={}, userInput={}", sessionId, userInput);

        if (userInput != null && !userInput.isBlank()) {
            stateManager.recordUserInput(sessionId, requestId, userInput);
            log.info("[PlannerNode] Recorded user input: sessionId={}", sessionId);
        }
        
        // 发布事件
        eventBus.publish(sessionId, new PlanningStartEvent(sessionId));
        
        // 加载可用 Workers
        List<Agent> workers = loadWorkers(sessionId);
        
        // 确定用户目标
        String userGoal = determineUserGoal(sessionId, userInput);
        
        log.debug("[PlannerNode] User goal: {}", userGoal);

        // 构建 Prompt（使用自定义 JSON Schema）
        String prompt = promptManager.buildPlannerPrompt(workers, userGoal);


        // 流式调用 LLM（带重试机制）
        PlannerResponseDTO dto;
        try {
            dto = generatePlanWithRetry(sessionId, prompt, 3);
        } catch (SessionStoppedException e) {
            log.info("[PlannerNode] Session stopped during planning: sessionId={}", sessionId);
            eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "USER_STOP"));
            return Map.of("nextAction", "cannot_plan");
        }

        // 解析结果
        return convertDtoToResult(sessionId, dto, workers);
    }
    
    /**
     * 带 Self-Correction 重试的计划生成
     */
    private PlannerResponseDTO generatePlanWithRetry(String sessionId, String initialPrompt, int maxRetries)
            throws Exception {

        String currentPrompt = initialPrompt;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("[PlannerNode] Attempt {}/{} to generate plan", attempt, maxRetries);

            StringBuilder response = new StringBuilder();
            Long messageId = null;

            try {
                // 获取 Planner Agent 信息
                Long plannerAgentId = getPlannerAgentId();
                String plannerAgentName = getPlannerAgentName();
                
                // 创建流式消息记录（只创建记录，不写入内容）
                messageId = stateManager.createStreamingMessage(sessionId, plannerAgentId, plannerAgentName);
                log.debug("[PlannerNode] Created streaming message: messageId={}", messageId);

                final Long finalMessageId = messageId;

                // 流式调用 LLM（在内存中收集完整响应）
                llmAdapter.streamChat(getPlannerAgentId(), sessionId, null, currentPrompt, null)
                        .doOnNext(token -> {
                            response.append(token);

                            // 仍然实时推送到前端
                            eventBus.publish(sessionId, new PlannerDeltaEvent(sessionId, token));
                        })
                        .doOnComplete(() -> {
                            log.debug("[PlannerNode] Stream completed for messageId={}", finalMessageId);
                        })
                        .doOnError(e -> {
                            log.error("[PlannerNode] Stream error for messageId={}", finalMessageId, e);
                        })
                        .blockLast();
                
                String fullResponse = response.toString();
                log.debug("[PlannerNode] Raw LLM response (attempt {}):\n{}", attempt, fullResponse);

                // 使用 BeanOutputConverter 解析响应
                PlannerResponseDTO dto = plannerOutputConverter.convert(fullResponse);
                log.info("[PlannerNode] ✅ Successfully parsed response on attempt {}", attempt);

                // 解析成功，一次性写入完整内容到数据库
                stateManager.completeStreamingMessage(messageId, fullResponse);

                return dto;

            } catch (Exception e) {
                lastException = e;
                log.warn("[PlannerNode] ❌ Parse failed on attempt {}: {}", attempt, e.getMessage());

                // 解析失败时保存部分内容并标记错误
                if (messageId != null) {
                    try {
                        stateManager.failStreamingMessage(messageId, response.toString(), e.getMessage());
                    } catch (Exception ex) {
                        log.error("[PlannerNode] Failed to mark message as failed on parse error", ex);
                    }
                }
                
                if (attempt < maxRetries) {
                    // 构造修正 Prompt
                    String errorMsg = e.getMessage();
                    String rawResponse = response.toString();
                    currentPrompt = buildCorrectionPrompt(errorMsg, rawResponse);
                    
                    log.info("[PlannerNode] 🔄 Sending correction prompt for attempt {}", attempt + 1);
                }
            }
        }
        
        // 重试耗尽
        throw new RuntimeException("Failed to parse plan after " + maxRetries + " attempts", lastException);
    }
    
    /**
     * 构造修正 Prompt
     */
    private String buildCorrectionPrompt(String errorMsg, String rawResponse) {
        return String.format("""
                你刚才生成的 JSON 解析失败。
                
                【错误信息】
                %s
                
                【原始响应】
                %s
                
                【要求】
                请仔细检查 JSON 格式，修正以下常见问题：
                1. 是否缺少引号、逗号、括号
                2. 字段名是否拼写正确（type, goal, steps, description, assignedAgentId 等）
                3. 确保 stepIndex 是数字类型
                4. 确保 steps 是数组类型
                
                请只输出修正后的纯 JSON，不要有任何解释或 Markdown 代码块标记。
                """,
                errorMsg,
                truncate(rawResponse, 1000)
        );
    }
    
    /**
     * 将 DTO 转换为 Map<String, Object>（返回给 Graph）
     */
    private Map<String, Object> convertDtoToResult(String sessionId, PlannerResponseDTO dto, List<Agent> workers) {
        String type = dto.getType();
        
        log.debug("[PlannerNode] Converting DTO to result: type={}, sessionId={}", type, sessionId);
        
        if (type == null || type.isBlank()) {
            log.warn("[PlannerNode] Type is null or blank, returning cannot_plan");
            return Map.of("nextAction", "cannot_plan");
        }
        
        switch (type) {
            case "plan" -> {
                // 校验必填字段
                if (dto.getGoal() == null || dto.getGoal().isBlank()) {
                    log.warn("[PlannerNode] Goal is null or blank, returning cannot_plan");
                    return Map.of("nextAction", "cannot_plan");
                }
                if (dto.getSteps() == null || dto.getSteps().isEmpty()) {
                    log.warn("[PlannerNode] Steps is null or empty, returning cannot_plan");
                    return Map.of("nextAction", "cannot_plan");
                }

                ExecutionPlan plan = convertDtoToPlan(sessionId, dto, workers);
                log.debug("[PlannerNode] Plan created: planId={}, goal={}, steps={}",
                        plan.getPlanId(),
                        plan.getGoal(),
                        plan.getSteps() != null ? plan.getSteps().size() : "null");

                // DAG 校验
                DAGValidator.ValidationResult validationResult = DAGValidator.validate(plan.getSteps());
                if (!validationResult.isValid()) {
                    log.warn("[PlannerNode] DAG validation failed: {}", validationResult.getErrorMessage());

                    // 降级为串行执行
                    log.warn("[PlannerNode] Degrading to sequential execution");
                    DAGValidator.degradeToSequential(plan.getSteps());
                    eventBus.publish(sessionId, new PlanGeneratedEvent(sessionId, plan));
                } else {
                    // 应用层级索引
                    Map<String, Integer> layerIndexMap = validationResult.getLayerIndexMap();
                    for (PlanStep step : plan.getSteps()) {
                        Integer layerIndex = layerIndexMap.get(step.getStepId());
                        if (layerIndex != null) {
                            step.setLayerIndex(layerIndex);
                        }
                    }
                    log.info("[PlannerNode] DAG validation passed, {} layers detected",
                            layerIndexMap.values().stream().max(Integer::compareTo).orElse(0) + 1);
                    eventBus.publish(sessionId, new PlanGeneratedEvent(sessionId, plan));
                }

                stateManager.savePlan(plan);

                log.info("[PlannerNode] Returning execution plan with {} steps", plan.getSteps().size());
                return Map.of(
                    "nextAction", "execute",
                    "currentStepIndex", 0
                );
            }
            case "question" -> {
                String question = dto.getContent();
                if (question == null || question.isBlank()) {
                    log.warn("[PlannerNode] Question content is null or blank, returning cannot_plan");
                    return Map.of("nextAction", "cannot_plan");
                }
                log.info("[PlannerNode] Returning clarification request: {}", question);
                Map<String, Object> result = new HashMap<>();
                result.put("nextAction", "clarify");
                result.put("clarifyQuestion", question);
                return result;
            }
            case "cannot_plan" -> {
                String reason = dto.getReason();
                log.info("[PlannerNode] Cannot plan: {}", reason != null ? reason : "未知原因");
                eventBus.publish(sessionId, new PlanFailedEvent(sessionId, reason != null ? reason : "未知原因"));
                return Map.of("nextAction", "cannot_plan");
            }
            default -> {
                log.warn("[PlannerNode] Unknown type: {}", type);
                return Map.of("nextAction", "cannot_plan");
            }
        }
    }
    
    /**
     * 将 DTO 转换为 ExecutionPlan
     */
    private ExecutionPlan convertDtoToPlan(String sessionId, PlannerResponseDTO dto, List<Agent> workers) {
        // 第一遍：验证所有 Worker ID
        List<String> invalidAgentIds = new ArrayList<>();
        for (var stepDto : dto.getSteps()) {
            Agent agent = findAgentById(workers, stepDto.getAssignedAgentId());
            if (agent == null) {
                invalidAgentIds.add(stepDto.getAssignedAgentId());
            }
        }

        // 如果有无效 ID，拒绝整个计划
        if (!invalidAgentIds.isEmpty()) {
            String availableWorkerIds = workers.stream()
                    .map(w -> String.valueOf(w.getId()))
                    .collect(java.util.stream.Collectors.joining(", "));
            String errorMsg = String.format("Invalid Worker IDs found: %s. Available Worker IDs: %s",
                    invalidAgentIds, availableWorkerIds);
            log.error("[PlannerNode] {}", errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        // 第二遍：创建 stepIndex -> stepId 映射
        Map<Integer, String> indexToIdMap = new HashMap<>();
        List<PlanStep> steps = new ArrayList<>();

        // 创建步骤并建立索引映射
        for (var stepDto : dto.getSteps()) {
            String stepId = UUID.randomUUID().toString();
            indexToIdMap.put(stepDto.getStepIndex(), stepId);

            Agent agent = findAgentById(workers, stepDto.getAssignedAgentId());

            PlanStep step = PlanStep.builder()
                    .stepId(stepId)
                    .stepIndex(stepDto.getStepIndex())
                    .assignedAgentId(stepDto.getAssignedAgentId())
                    .assignedAgentName(agent != null ? agent.getName() : "Unknown")
                    .instruction(stepDto.getInstruction())
                    .expectedOutput(stepDto.getExpectedOutput())
                    .status(StepStatus.PENDING)
                    .build();

            steps.add(step);
        }

        // 第三遍：转换 dependsOn（从 stepIndex 转换为 stepId）
        for (int i = 0; i < dto.getSteps().size(); i++) {
            var stepDto = dto.getSteps().get(i);
            var step = steps.get(i);

            if (stepDto.getDependsOn() != null && !stepDto.getDependsOn().isEmpty()) {
                List<String> dependsOnIds = stepDto.getDependsOn().stream()
                        .map(indexToIdMap::get)
                        .filter(Objects::nonNull)
                        .toList();
                step.setDependsOn(dependsOnIds);
            }
        }

        return ExecutionPlan.builder()
                .planId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .goal(dto.getGoal())
                .steps(steps)
                .currentStepIndex(0)
                .status(PlanStatus.EXECUTING)
                .build();
    }
    
    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null || str.length() <= maxLen) {
            return str;
        }
        return str.substring(0, maxLen) + "\n...[省略 " + (str.length() - maxLen) + " 字符]";
    }
    
    /**
     * 确定用户目标
     * 优先使用当前 userInput，如果为空则从历史 plan 中获取
     */
    private String determineUserGoal(String sessionId, String userInput) {
        // 1. 优先使用当前用户输入
        if (userInput != null && !userInput.isBlank()) {
            log.debug("[PlannerNode] Using current userInput as goal: {}", userInput);
            return userInput;
        }
        
        // 2. 如果当前输入为空，尝试从已有 plan 中获取 goal（Human-in-the-loop 场景）
        ExecutionPlan existingPlan = stateManager.loadPlan(sessionId);
        if (existingPlan != null && existingPlan.getGoal() != null && !existingPlan.getGoal().isBlank()) {
            log.debug("[PlannerNode] Using existing plan goal: {}", existingPlan.getGoal());
            return existingPlan.getGoal();
        }
        
        // 3. 如果都没有，返回 null（会触发 LLM 询问用户）
        log.warn("[PlannerNode] No user goal found for session: {}", sessionId);
        return null;
    }
    
    /**
     * 加载可用 Worker
     */
    private List<Agent> loadWorkers(String sessionId) {
        try {
            var sessionAgents = sessionService.getSessionAgents(sessionId);
            if (sessionAgents.isEmpty()) {
                return agentService.getAllAgents();
            }
            
            return sessionAgents.stream()
                    .map(sa -> {
                        try {
                            return agentService.getAgentById(sa.getAgentId());
                        } catch (Exception e) {
                            log.warn("[PlannerNode] Failed to load agent: {}", sa.getAgentId());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.error("[PlannerNode] Failed to load workers", e);
            return List.of();
        }
    }
    
    /**
     * 根据 ID 查找 Agent
     */
    private Agent findAgentById(List<Agent> workers, String agentId) {
        return workers.stream()
                .filter(w -> agentId.equals(String.valueOf(w.getId())))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 获取 Planner Agent ID
     */
    private Long getPlannerAgentId() {
        Agent plannerAgent = getPlannerAgent();
        return plannerAgent != null ? plannerAgent.getId() : null;
    }
    
    /**
     * 获取 Planner Agent Name
     */
    private String getPlannerAgentName() {
        Agent plannerAgent = getPlannerAgent();
        return plannerAgent != null ? plannerAgent.getName() : "Planner";
    }
    
    /**
     * 获取 Planner Agent
     */

    private Agent getPlannerAgent() {
        try {
            return agentMapper.selectOne(
                    new LambdaQueryWrapper<Agent>()
                            .eq(Agent::getRoleType, "PLANNER")
                            .last("LIMIT 1")
            );
        } catch (Exception e) {
            log.error("[PlannerNode] Failed to get planner agent", e);
            return null;
        }
    }
}
