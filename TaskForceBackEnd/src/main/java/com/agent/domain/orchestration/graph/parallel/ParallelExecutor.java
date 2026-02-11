package com.agent.domain.orchestration.graph.parallel;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.service.ContextService;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.model.StepResult;
import com.agent.domain.orchestration.model.StepStatus;
import com.agent.domain.orchestration.state.StateManager;
import com.agent.common.exception.SessionStoppedException;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.StepCompletedEvent;
import com.agent.infrastructure.event.events.StepStartEvent;
import com.agent.infrastructure.event.events.WorkerDeltaEvent;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.service.SessionStopService;
import com.agent.service.SessionExecutionTracker;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final SessionExecutionTracker executionTracker;
    private final ReactAgentFactory reactAgentFactory;
    private final PromptManager promptManager;

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
            Mono<Void> stepMono = Mono.fromCallable(() -> {
                try {
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

                    return null;

                } catch (SessionStoppedException e) {
                    log.info("[ParallelExecutor] Session stopped during step execution: sessionId={}, stepId={}",
                            sessionId, step.getStepId());
                    results.put(step.getStepId(), StepResult.blocked("Session stopped"));
                    return null;
                } catch (Exception e) {
                    log.error("[ParallelExecutor] Failed to execute step: stepId={}", step.getStepId(), e);
                    results.put(step.getStepId(), StepResult.blocked("执行失败: " + e.getMessage()));
                    return null;
                }
            })
            .then()  // 转换为 Mono<Void>
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(Duration.ofMinutes(10))
            .onErrorResume(e -> {
                if (e instanceof SessionStoppedException) {
                    log.info("[ParallelExecutor] Step cancelled by stop signal: stepId={}", step.getStepId());
                    results.put(step.getStepId(), StepResult.blocked("Session stopped"));
                } else {
                    log.error("[ParallelExecutor] Step execution error: stepId={}", step.getStepId(), e);
                    results.put(step.getStepId(), StepResult.blocked("执行失败: " + e.getMessage()));
                }
                return Mono.empty();
            });

            stepMonos.add(stepMono);
        }

        // 并行执行所有步骤，并注册 Disposable
        // 用于跟踪取消状态
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Flux.merge(stepMonos)
                .doOnSubscribe(subscription -> {
                    // 注册 Disposable 到跟踪器
                    Disposable trackableDisposable = new Disposable() {
                        @Override
                        public void dispose() {
                            cancelled.set(true);
                            subscription.cancel();
                        }

                        @Override
                        public boolean isDisposed() {
                            return cancelled.get();
                        }
                    };
                    executionTracker.registerDisposable(sessionId, trackableDisposable);
                    log.debug("[ParallelExecutor] Registered parallel execution disposable for session: {}", sessionId);
                })
                .doFinally(signalType -> {
                    // 清理已完成的任务
                    executionTracker.cleanup(sessionId);
                    log.debug("[ParallelExecutor] Cleaned up session: {}", sessionId);
                })
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

            // 3. 使用 PromptManager 构建完整的 Worker Prompt（包含工作空间说明、执行协议等）
            String fullInstruction = promptManager.buildWorkerPromptWithAssembledContext(
                    assembledContext, worker, step);

            // 4. 构建 ReactAgent
            ReactAgent reactAgent = reactAgentFactory.buildWorkerReactAgent(
                    Long.valueOf(step.getAssignedAgentId()),
                    fullInstruction,
                    MAX_REACT_ITERATIONS,
                    sessionId,
                    step.getStepId(),
                    step.getStepIndex(),
                    null // 旧编排模式不使用 Worker 实例
            );

            // 5. 创建流式消息记录
            Long messageId = stateManager.createStreamingMessage(sessionId, step);

            // 6. 配置 RunnableConfig
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId + "_" + step.getStepId()) // 使用唯一的 threadId
                    .build();

            // 7. 使用 ReactAgent 流式执行（在内存中收集完整响应）
            StringBuilder response = new StringBuilder();

            reactAgent.stream(fullInstruction, config)
                    .doOnNext(nodeOutput -> {
                        if (nodeOutput instanceof StreamingOutput streamingOutput) {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                response.append(chunk);

                                // 仍然发布实时事件给前端
                                eventBus.publish(sessionId, new WorkerDeltaEvent(sessionId, step.getStepId(), chunk));
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        // 完成时一次性写入数据库
                        stateManager.completeStreamingMessage(messageId, response.toString());
                        contextService.saveStepOutput(sessionId, step.getStepIndex(), response.toString());
                    })
                    .doOnError(e -> {
                        // 错误时保存部分内容
                        stateManager.failStreamingMessage(messageId, response.toString(), e.getMessage());
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
