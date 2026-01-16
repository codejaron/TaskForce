package com.agent.application.orchestration;

import com.agent.application.orchestration.dto.ReplanResponseDTO;
import com.agent.domain.model.plan.*;
import com.agent.entity.Agent;
import com.agent.infrastructure.context.SessionContextHolder;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.mapper.AgentMapper;
import com.agent.model.AgentProfile;
import com.agent.service.AgentProfileService;
import com.agent.service.SessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

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
    private final PromptManager promptManager;
    private final AgentMapper agentMapper;
    private final AgentProfileService agentProfileService;
    private final SessionService sessionService;

    // BeanOutputConverter for automatic JSON parsing
    private final BeanOutputConverter<ReplanResponseDTO> replanOutputConverter =
            new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});

    /**
     * 重规划（使用 BeanOutputConverter 和 Self-Correction 重试机制）
     */
    public ExecutionPlan replan(String sessionId, ExecutionPlan currentPlan, String blockedReason) {
        log.info("[ReplannerAgent] Replanning for session: {}, replanCount: {}",
                sessionId, currentPlan.getReplanCount());

        try {
            //设置 SessionContext，使 Replanner 调用的工具能获取 sessionId
            SessionContextHolder.setSessionId(sessionId);
            log.debug("[ReplannerAgent] SessionContext set for replanning phase: sessionId={}", sessionId);

            PlanStep blockedStep = currentPlan.getCurrentStep();

            // 加载可用的 Workers
            List<AgentProfile> workers = loadWorkers(sessionId);
            String workersInfo = formatWorkersInfo(workers);

            // 获取 BeanOutputConverter 的格式说明
            String formatInstructions = replanOutputConverter.getFormat();

            // 构建 Prompt（包含 workers 信息和格式说明）
            String prompt = promptManager.buildReplannerPrompt(
                    currentPlan.getGoal(),
                    currentPlan.getCompletedStepCount(),
                    currentPlan.getSteps().size(),
                    blockedStep != null ? blockedStep.getStepIndex() : 0,
                    blockedStep != null ? blockedStep.getDescription() : "Unknown",
                    blockedReason,
                    workersInfo,
                    formatInstructions
            );
            log.debug("[ReplannerAgent] 🔄 重规划 Prompt:\n{}", prompt);

            try {
                ReplanResponseDTO dto = replanWithRetry(sessionId, prompt, 3);
                return convertDtoToExecutionPlan(sessionId, currentPlan, dto, workers);
            } catch (Exception e) {
                log.error("[ReplannerAgent] Replan failed after all retries", e);
                currentPlan.markFailed("重规划失败（已重试 3 次）: " + e.getMessage());
                return currentPlan;
            }
        } finally {
            // 清理 SessionContext
            SessionContextHolder.clear();
            log.debug("[ReplannerAgent] SessionContext cleared after replanning phase");
        }
    }

    /**
     * 格式化 Workers 信息为字符串
     */
    private String formatWorkersInfo(List<AgentProfile> workers) {
        if (workers == null || workers.isEmpty()) {
            return "暂无可用的 Worker";
        }

        StringBuilder sb = new StringBuilder();
        for (AgentProfile worker : workers) {
            sb.append(String.format("- ID: %s, 名称: %s, 描述: %s\n",
                    worker.getId(),
                    worker.getName(),
                    worker.getDescription() != null ? worker.getDescription() : "通用任务处理"));
        }
        return sb.toString();
    }

    /**
     * 带 Self-Correction 重试的重规划
     *
     * @param sessionId      会话 ID
     * @param initialPrompt  初始 Prompt
     * @param maxRetries     最大重试次数（固定为 3）
     * @return 成功解析的 ReplanResponseDTO
     * @throws Exception 重试耗尽后抛出
     */
    private ReplanResponseDTO replanWithRetry(String sessionId, String initialPrompt, int maxRetries)
            throws Exception {

        String currentPrompt = initialPrompt;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("[ReplannerAgent] Replan attempt {}/{}", attempt, maxRetries);

            StringBuilder response = new StringBuilder();

            try {
                // 流式调用 LLM（累积响应，继续发送 delta 事件保持用户体验）
                llmAdapter.streamChat(getPlannerAgentId(), sessionId, currentPrompt, null)
                        .doOnNext(token -> {
                            response.append(token);
                            // 发送 delta 事件（保持现有行为）
                            eventBus.publish(sessionId, new ReplannerDeltaEvent(sessionId, token));
                        })
                        .blockLast();

                String fullResponse = response.toString();
                log.debug("[ReplannerAgent] Raw response (attempt {}):\n{}", attempt, fullResponse);

                // 使用 BeanOutputConverter 解析响应
                ReplanResponseDTO dto = replanOutputConverter.convert(fullResponse);
                log.info("[ReplannerAgent] ✅ Successfully parsed replan on attempt {}", attempt);
                return dto;

            } catch (Exception e) {
                lastException = e;
                log.warn("[ReplannerAgent] ❌ Parse failed on attempt {}: {}", attempt, e.getMessage());

                if (attempt < maxRetries) {
                    // 构造修正 Prompt
                    String errorMsg = e.getMessage();
                    String rawResponse = response.toString();
                    currentPrompt = buildCorrectionPromptForReplan(errorMsg, rawResponse);

                    log.info("[ReplannerAgent] 🔄 Sending correction prompt for attempt {}", attempt + 1);
                }
            }
        }

        // 重试耗尽，抛出最后一次异常
        throw new RuntimeException("Failed to parse replan after " + maxRetries + " attempts", lastException);
    }

    /**
     * 构造重规划修正 Prompt
     */
    private String buildCorrectionPromptForReplan(String errorMsg, String rawResponse) {
        return String.format("""
                重规划的 JSON 解析失败。

                【错误信息】
                %s

                【原始响应】
                %s

                【要求】
                请修正 JSON 格式，确保：
                1. 包含 "type": "plan"
                2. 包含 "goal" 字段
                3. 包含 "steps" 数组，每个步骤包含 stepIndex, description, assignedAgentId, instruction, expectedOutput
                4. stepIndex 是数字类型

                只输出修正后的纯 JSON，不要有任何解释或 Markdown 代码块标记。
                """,
                errorMsg,
                truncate(rawResponse, 1000));
    }

    /**
     * 将 DTO 转换为 ExecutionPlan
     */
    private ExecutionPlan convertDtoToExecutionPlan(String sessionId, ExecutionPlan currentPlan, ReplanResponseDTO dto, List<AgentProfile> workers) {
        if (dto.getSteps() == null || dto.getSteps().isEmpty()) {
            throw new IllegalArgumentException("Replan steps cannot be empty");
        }

        List<PlanStep> newSteps = dto.getSteps().stream()
                .map(stepDto -> {
                    // 根据agentId查找Agent信息
                    AgentProfile agent = findAgentById(workers, stepDto.getAssignedAgentId());

                    return PlanStep.builder()
                            .stepId(UUID.randomUUID().toString())
                            .stepIndex(stepDto.getStepIndex())  // 保持LLM的1-based
                            .description(stepDto.getDescription())
                            .assignedAgentId(stepDto.getAssignedAgentId())
                            .assignedAgentName(agent != null ? agent.getName() : "Unknown")  // 设置Agent名称
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
                .goal(dto.getGoal() != null ? dto.getGoal() : currentPlan.getGoal())
                .steps(newSteps)
                .currentStepIndex(0)  // 重规划后从第一个步骤开始
                .status(PlanStatus.EXECUTING)
                .replanCount(currentPlan.getReplanCount() + 1)
                .build();
    }

    /**
     * 截断字符串（避免 Token 过多）
     */
    private String truncate(String str, int maxLen) {
        if (str == null || str.length() <= maxLen) {
            return str;
        }
        return str.substring(0, maxLen) + "\n...[省略 " + (str.length() - maxLen) + " 字符]";
    }

    /**
     * 获取 Planner Agent ID
     * 从数据库中查找 role_type='PLANNER' 的 Agent，复用 Planner 的模型配置
     */
    private Long getPlannerAgentId() {
        try {
            Agent plannerAgent = agentMapper.selectOne(
                    new LambdaQueryWrapper<Agent>()
                            .eq(Agent::getRoleType, "PLANNER")
                            .last("LIMIT 1")
            );

            if (plannerAgent != null) {
                log.debug("[ReplannerAgent] Found PLANNER agent: id={}, name={}",
                        plannerAgent.getId(), plannerAgent.getName());
                return plannerAgent.getId();
            }

            log.warn("[ReplannerAgent] No PLANNER agent found in database");
            return null;

        } catch (Exception e) {
            log.error("[ReplannerAgent] Failed to get planner agent ID", e);
            return null;
        }
    }

    /**
     * 加载可用的Worker列表
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
            log.error("[ReplannerAgent] Failed to load workers", e);
            return List.of();
        }
    }

    /**
     * 根据ID查找Agent
     */
    private AgentProfile findAgentById(List<AgentProfile> workers, String agentId) {
        if (agentId == null || workers == null) {
            return null;
        }
        return workers.stream()
                .filter(w -> agentId.equals(String.valueOf(w.getId())))
                .findFirst()
                .orElse(null);
    }
}
