package com.agent.domain.orchestration.graph.parallel;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.service.ContextService;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.model.StepResult;
import com.agent.domain.orchestration.model.StepStatus;
import com.agent.domain.orchestration.state.StateManager;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.StepCompletedEvent;
import com.agent.infrastructure.event.events.StepStartEvent;
import com.agent.infrastructure.event.events.WorkerDeltaEvent;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.service.SessionStopService;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 并行执行器
 * 负责并行执行同一层级的多个步骤
 */
@Slf4j
@RequiredArgsConstructor
public class ParallelExecutor {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final ContextService contextService;
    private final ContextAssembler contextAssembler;
    private final SessionStopService sessionStopService;
    private final ReactAgentFactory reactAgentFactory;

    private static final int MAX_REACT_ITERATIONS = 20;

    /**
     * 并行执行一层的所有步骤
     *
     * @param sessionId 会话 ID
     * @param layerSteps 当前层的所有步骤
     * @return 所有步骤的执行结果
     */
    public Map<String, StepResult> executeLayer(String sessionId, List<PlanStep> layerSteps) {
        log.info("[ParallelExecutor] Executing layer with {} steps in parallel", layerSteps.size());

        Map<String, StepResult> results = new ConcurrentHashMap<>();

        // 创建并行执行的 Flux
        List<Mono<Void>> stepMonos = new ArrayList<>();

        for (PlanStep step : layerSteps) {
            Mono<Void> stepMono = Mono.fromRunnable(() -> {
                try {
                    // 检查是否需要停止
                    if (sessionStopService.shouldStop(sessionId)) {
                        log.info("[ParallelExecutor] Session stopped: sessionId={}, stepId={}",
                                sessionId, step.getStepId());
                        results.put(step.getStepId(), StepResult.blocked("Session stopped"));
                        return;
                    }

                    log.info("[ParallelExecutor] Executing step: stepId={}, instruction={}",
                            step.getStepId(), step.getInstruction());

                    // 发布开始事件
                    eventBus.publish(sessionId, new StepStartEvent(
                            sessionId,
                            step.getStepId(),
                            step.getStepIndex(),
                            step.getInstruction(),
                            step.getAssignedAgentId(),
                            step.getAssignedAgentName()
                    ));

                    // 执行步骤
                    StepResult result = executeStep(sessionId, step);
                    results.put(step.getStepId(), result);

                    // 更新步骤状态
                    if (result.isSuccess()) {
                        step.setStatus(StepStatus.DONE);
                    } else if (result.isBlocked()) {
                        step.setStatus(StepStatus.BLOCKED);
                    }

                    // 发布完成事件
                    eventBus.publish(sessionId, new StepCompletedEvent(
                            sessionId,
                            step.getStepId(),
                            step.getStepIndex(),
                            result.getOutput()
                    ));

                } catch (Exception e) {
                    log.error("[ParallelExecutor] Failed to execute step: stepId={}", step.getStepId(), e);
                    results.put(step.getStepId(), StepResult.blocked("执行失败: " + e.getMessage()));
                }
            });

            stepMonos.add(stepMono);
        }

        // 并行执行所有步骤
        Flux.merge(stepMonos)
                .then()
                .block();

        log.info("[ParallelExecutor] Layer execution completed, {} results", results.size());
        return results;
    }

    /**
     * 执行单个步骤（使用 ReactAgent）
     */
    private StepResult executeStep(String sessionId, PlanStep step) {
        try {
            // 1. 加载 Worker 配置
            Agent worker = stateManager.loadAgent(step.getAssignedAgentId());
            if (worker == null) {
                log.error("[ParallelExecutor] Worker not found: {}", step.getAssignedAgentId());
                return StepResult.blocked("Worker not found: " + step.getAssignedAgentId());
            }

            // 2. 组装上下文
            ExecutionPlan plan = stateManager.loadPlan(sessionId);
            String assembledContext = contextAssembler.assemble(plan, step.getStepIndex());

            // 3. 构建完整的执行指令
            String fullInstruction = assembledContext + "\n\n" + step.getInstruction();

            // 4. 构建 ReactAgent
            ReactAgent reactAgent = reactAgentFactory.buildWorkerReactAgent(
                    Long.valueOf(step.getAssignedAgentId()),
                    fullInstruction,
                    MAX_REACT_ITERATIONS
            );

            // 5. 创建流式消息记录
            Long messageId = stateManager.createStreamingMessage(sessionId, step);

            // 6. 配置 RunnableConfig
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId + "_" + step.getStepId()) // 使用唯一的 threadId
                    .build();

            // 7. 使用 ReactAgent 流式执行
            StringBuilder response = new StringBuilder();
            StringBuilder buffer = new StringBuilder();
            final int FLUSH_THRESHOLD = 100;

            reactAgent.stream(fullInstruction, config)
                    .doOnNext(nodeOutput -> {
                        if (nodeOutput instanceof StreamingOutput streamingOutput) {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                response.append(chunk);
                                buffer.append(chunk);

                                // 发布流式事件
                                eventBus.publish(sessionId, new WorkerDeltaEvent(sessionId, step.getStepId(), chunk));

                                // 批量持久化
                                if (buffer.length() >= FLUSH_THRESHOLD) {
                                    stateManager.appendStreamingContent(messageId, buffer.toString());
                                    buffer.setLength(0);
                                }
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        if (buffer.length() > 0) {
                            stateManager.appendStreamingContent(messageId, buffer.toString());
                        }
                        stateManager.completeStreamingMessage(messageId, response.toString());
                        contextService.saveStepOutput(sessionId, step.getStepIndex(), response.toString());
                    })
                    .doOnError(e -> {
                        if (buffer.length() > 0) {
                            stateManager.appendStreamingContent(messageId, buffer.toString());
                        }
                        stateManager.completeStreamingMessage(messageId, response.toString());
                        contextService.saveStepOutput(sessionId, step.getStepIndex(), response.toString());
                    })
                    .blockLast();

            String output = response.toString();

            // 8. 解析结果状态
            return parseStepResult(output);

        } catch (Exception e) {
            log.error("[ParallelExecutor] Failed to execute step", e);
            return StepResult.blocked("执行失败: " + e.getMessage());
        }
    }

    /**
     * 解析步骤结果
     */
    private StepResult parseStepResult(String response) {
        if (response.contains("BLOCKED:")) {
            String reason = extractAfterMarker(response, "BLOCKED:");
            return StepResult.blocked(reason);
        }

        if (response.contains("NEED_USER_INPUT:")) {
            String question = extractAfterMarker(response, "NEED_USER_INPUT:");
            return StepResult.needsUserInput(question);
        }

        return StepResult.success(response);
    }

    private String extractAfterMarker(String response, String marker) {
        int idx = response.indexOf(marker);
        if (idx >= 0) {
            String after = response.substring(idx + marker.length()).trim();
            int newline = after.indexOf("\n");
            if (newline > 0) {
                return after.substring(0, newline).trim();
            }
            return after;
        }
        return response;
    }
}
