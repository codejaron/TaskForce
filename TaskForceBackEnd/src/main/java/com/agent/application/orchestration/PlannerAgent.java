package com.agent.application.orchestration;

import com.agent.application.orchestration.dto.PlannerResponseDTO;
import com.agent.domain.model.plan.*;
import com.agent.entity.Agent;
import com.agent.entity.Message;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.mapper.AgentMapper;
import com.agent.mapper.MessageMapper;
import com.agent.model.AgentProfile;
import com.agent.service.AgentProfileService;
import com.agent.service.SessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

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
    private final AgentMapper agentMapper;
    private final MessageMapper messageMapper;
    private final PromptManager promptManager;

    // BeanOutputConverter for automatic JSON parsing
    private final BeanOutputConverter<PlannerResponseDTO> plannerOutputConverter =
            new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});

    /**
     * 生成计划（使用 BeanOutputConverter 和 Self-Correction 重试机制）
     */
    public PlannerResult generatePlan(String sessionId, String userGoal) {
        log.info("[PlannerAgent] Generating plan for session: {}", sessionId);

        // 获取可用 Worker 列表
        List<AgentProfile> workers = loadWorkers(sessionId);

        // 构建包含历史澄清的上下文化目标
        String contextualGoal = buildContextualGoal(sessionId, userGoal);

        // 获取 BeanOutputConverter 的格式说明
        String formatInstructions = plannerOutputConverter.getFormat();

        // 构建 Prompt（包含格式说明和上下文）
        String prompt = promptManager.buildPlannerPrompt(workers, contextualGoal, formatInstructions);
        log.debug("[PlannerAgent] 📝 发送给 LLM 的完整 Prompt:\n{}", prompt);

        // 使用 Self-Correction 重试机制生成计划
        try {
            PlannerResponseDTO dto = generatePlanWithRetry(sessionId, prompt, 3);
            return convertDtoToResult(sessionId, dto, workers);
        } catch (Exception e) {
            log.error("[PlannerAgent] Failed to generate plan after all retries", e);
            return new PlannerResult.CannotPlan("规划失败（已重试 3 次）: " + e.getMessage());
        }
    }

    /**
     * 带 Self-Correction 重试的计划生成
     * 当 JSON 解析失败时，将错误信息返回给 LLM 让其自我修正
     *
     * @param sessionId      会话 ID
     * @param initialPrompt  初始 Prompt
     * @param maxRetries     最大重试次数（固定为 3）
     * @return 成功解析的 PlannerResponseDTO
     * @throws Exception 重试耗尽后抛出
     */
    private PlannerResponseDTO generatePlanWithRetry(String sessionId, String initialPrompt, int maxRetries)
            throws Exception {

        String currentPrompt = initialPrompt;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("[PlannerAgent] Attempt {}/{} to generate plan", attempt, maxRetries);

            StringBuilder response = new StringBuilder();

            try {
                // 流式调用 LLM（累积响应，不发送 delta 事件）
                llmAdapter.streamChat(getPlannerAgentId(), sessionId, currentPrompt, null)
                        .doOnNext(response::append)
                        .blockLast();

                String fullResponse = response.toString();
                log.debug("[PlannerAgent] Raw LLM response (attempt {}):\n{}", attempt, fullResponse);

                // 使用 BeanOutputConverter 解析响应
                PlannerResponseDTO dto = plannerOutputConverter.convert(fullResponse);
                log.info("[PlannerAgent] ✅ Successfully parsed response on attempt {}", attempt);
                return dto;

            } catch (Exception e) {
                lastException = e;
                log.warn("[PlannerAgent] ❌ Parse failed on attempt {}: {}", attempt, e.getMessage());

                if (attempt < maxRetries) {
                    // 构造修正 Prompt，让 LLM 自我修正
                    String errorMsg = e.getMessage();
                    String rawResponse = response.toString();
                    currentPrompt = buildCorrectionPrompt(errorMsg, rawResponse);

                    log.info("[PlannerAgent] 🔄 Sending correction prompt for attempt {}", attempt + 1);
                }
            }
        }

        // 重试耗尽，抛出最后一次异常
        throw new RuntimeException("Failed to parse plan after " + maxRetries + " attempts", lastException);
    }

    /**
     * 构造修正 Prompt（让 LLM 自己修正格式错误）
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
                truncate(rawResponse, 1000) // 限制长度，避免 Token 过多
        );
    }

    /**
     * 将 DTO 转换为 PlannerResult
     */
    private PlannerResult convertDtoToResult(String sessionId, PlannerResponseDTO dto, List<AgentProfile> workers) {
        String type = dto.getType();

        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Response type cannot be null or empty");
        }

        return switch (type) {
            case "plan" -> {
                // 校验必填字段
                if (dto.getGoal() == null || dto.getGoal().isBlank()) {
                    throw new IllegalArgumentException("Goal is required when type=plan");
                }
                if (dto.getSteps() == null || dto.getSteps().isEmpty()) {
                    throw new IllegalArgumentException("Steps cannot be empty when type=plan");
                }

                ExecutionPlan plan = convertDtoToPlan(sessionId, dto, workers);
                yield new PlannerResult.PlanGenerated(plan);
            }
            case "question" -> {
                String question = dto.getContent();
                if (question == null || question.isBlank()) {
                    throw new IllegalArgumentException("Question content cannot be empty when type=question");
                }
                yield new PlannerResult.NeedClarification(question);
            }
            case "cannot_plan" -> {
                String reason = dto.getReason();
                yield new PlannerResult.CannotPlan(reason != null ? reason : "未知原因");
            }
            default -> {
                log.warn("[PlannerAgent] Unknown type: {}", type);
                yield new PlannerResult.CannotPlan("Unknown response type: " + type);
            }
        };
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
                            .stepIndex(stepDto.getStepIndex() - 1)  // LLM的1-based转换为内部0-based
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
                .currentStepIndex(0)  // 总是从第一个步骤开始（内部索引0）
                .status(PlanStatus.EXECUTING)
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
            return null;
        }
    }

    /**
     * 构建包含上下文的用户目标
     * 如果是Planner澄清后的恢复，需要结合历史消息
     */
    private String buildContextualGoal(String sessionId, String userGoal) {
        try {
            QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("session_id", sessionId)
                       .eq("role", "user")
                       .orderByDesc("created_at")
                       .last("LIMIT 3");

            List<Message> recentInputs = messageMapper.selectList(queryWrapper);

            if (recentInputs.size() > 1) {
                // 多条用户输入，表示有澄清过程
                StringBuilder contextual = new StringBuilder();
                contextual.append("【原始目标】\n");
                contextual.append(recentInputs.get(recentInputs.size() - 1).getContent());

                for (int i = recentInputs.size() - 2; i >= 0; i--) {
                    contextual.append("\n\n【用户补充澄清 ").append(recentInputs.size() - i - 1).append("】\n");
                    contextual.append(recentInputs.get(i).getContent());
                }

                return contextual.toString();
            }
        } catch (Exception e) {
            log.warn("[PlannerAgent] Failed to build contextual goal", e);
        }

        return userGoal;
    }
}
