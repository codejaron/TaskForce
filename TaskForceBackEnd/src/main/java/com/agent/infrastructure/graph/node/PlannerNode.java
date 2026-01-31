package com.agent.infrastructure.graph.node;

import com.agent.application.orchestration.StateManager;
import com.agent.application.orchestration.dto.PlannerResponseDTO;
import com.agent.domain.model.context.TaskContext;
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
 * Planner Node（从 PlannerAgent 改造）
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
    private final AgentProfileService agentProfileService;
    private final SessionService sessionService;
    private final AgentMapper agentMapper;
    
    private final BeanOutputConverter<PlannerResponseDTO> plannerOutputConverter =
            new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        String requestId = state.value("requestId", "");
        String userInput = state.value("userInput", "");
        
        log.info("[PlannerNode] Starting: sessionId={}, userInput={}", sessionId, userInput);
        
        // 发布事件
        eventBus.publish(sessionId, new PlanningStartEvent(sessionId));
        
        // 加载可用 Workers
        List<AgentProfile> workers = loadWorkers(sessionId);
        
        // 确定用户目标
        String userGoal = determineUserGoal(sessionId, userInput);
        
        log.debug("[PlannerNode] User goal: {}", userGoal);
        
        // 构建 Prompt
        String formatInstructions = plannerOutputConverter.getFormat();
        String prompt = promptManager.buildPlannerPrompt(workers, userGoal, formatInstructions);
        
        log.debug("[PlannerNode] Prompt:\n{}", prompt);
        
        // 流式调用 LLM（带重试机制）
        PlannerResponseDTO dto = generatePlanWithRetry(sessionId, prompt, 3);
        
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
            
            try {
                // 流式调用 LLM
                llmAdapter.streamChat(getPlannerAgentId(), sessionId, null, currentPrompt, null)
                        .doOnNext(token -> {
                            response.append(token);
                            eventBus.publish(sessionId, new PlannerDeltaEvent(sessionId, token));
                        })
                        .blockLast();
                
                String fullResponse = response.toString();
                log.debug("[PlannerNode] Raw LLM response (attempt {}):\n{}", attempt, fullResponse);
                
                // 使用 BeanOutputConverter 解析响应
                PlannerResponseDTO dto = plannerOutputConverter.convert(fullResponse);
                log.info("[PlannerNode] ✅ Successfully parsed response on attempt {}", attempt);
                return dto;
                
            } catch (Exception e) {
                lastException = e;
                log.warn("[PlannerNode] ❌ Parse failed on attempt {}: {}", attempt, e.getMessage());
                
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
    private Map<String, Object> convertDtoToResult(String sessionId, PlannerResponseDTO dto, List<AgentProfile> workers) {
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

                stateManager.savePlan(plan);
                eventBus.publish(sessionId, new PlanGeneratedEvent(sessionId, plan));
                
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
    private ExecutionPlan convertDtoToPlan(String sessionId, PlannerResponseDTO dto, List<AgentProfile> workers) {
        List<PlanStep> steps = dto.getSteps().stream()
                .map(stepDto -> {
                    AgentProfile agent = findAgentById(workers, stepDto.getAssignedAgentId());
                    
                    return PlanStep.builder()
                            .stepId(UUID.randomUUID().toString())
                            .stepIndex(stepDto.getStepIndex())
                            .description(stepDto.getDescription())
                            .assignedAgentId(stepDto.getAssignedAgentId())
                            .assignedAgentName(agent != null ? agent.getName() : "Unknown")
                            .requiredCapability(stepDto.getRequiredCapability())
                            .instruction(stepDto.getInstruction())
                            .expectedOutput(stepDto.getExpectedOutput())
                            .status(StepStatus.PENDING)
                            .build();
                })
                .toList();
        
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
    private List<AgentProfile> loadWorkers(String sessionId) {
        try {
            var sessionAgents = sessionService.getSessionAgents(sessionId);
            if (sessionAgents.isEmpty()) {
                return agentProfileService.listAll();
            }
            
            return sessionAgents.stream()
                    .map(sa -> agentProfileService.findById(String.valueOf(sa.getAgentId())))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        } catch (Exception e) {
            log.error("[PlannerNode] Failed to load workers", e);
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
     */
    private Long getPlannerAgentId() {
        try {
            Agent plannerAgent = agentMapper.selectOne(
                    new LambdaQueryWrapper<Agent>()
                            .eq(Agent::getRoleType, "PLANNER")
                            .last("LIMIT 1")
            );
            
            if (plannerAgent != null) {
                log.debug("[PlannerNode] Found PLANNER agent: id={}, name={}",
                        plannerAgent.getId(), plannerAgent.getName());
                return plannerAgent.getId();
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("[PlannerNode] Failed to get planner agent ID", e);
            return null;
        }
    }
}
