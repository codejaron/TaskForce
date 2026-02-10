package com.agent.domain.orchestration.graph.node;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.service.ContextService;
import com.agent.domain.orchestration.state.StateManager;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.model.StepResult;
import com.agent.domain.orchestration.model.StepStatus;
import com.agent.domain.orchestration.graph.parallel.ParallelExecutor;
import com.agent.common.exception.SessionStoppedException;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.service.SessionStopService;
import com.agent.service.SessionExecutionTracker;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Worker Node
 * 负责按层级并行执行计划步骤
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final ContextService contextService;
    private final ContextAssembler contextAssembler;
    private final SessionStopService sessionStopService;
    private final SessionExecutionTracker executionTracker;
    private final ReactAgentFactory reactAgentFactory;
    private final PromptManager promptManager;

    private static final int MAX_REACT_ITERATIONS = 20; // 最大 ReAct 循环次数

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        int currentLayerIndex = state.value("currentLayerIndex", 0);

        log.info("[WorkerNode] Executing layer {}: sessionId={}", currentLayerIndex, sessionId);

        // 加载计划
        ExecutionPlan plan = stateManager.loadPlan(sessionId);

        // 按层级分组步骤
        Map<Integer, List<PlanStep>> layerMap = plan.getSteps().stream()
                .collect(Collectors.groupingBy(PlanStep::getLayerIndex));

        int maxLayer = layerMap.keySet().stream().max(Integer::compareTo).orElse(0);

        // 检查当前层是否超出范围
        if (currentLayerIndex > maxLayer) {
            // 所有层级执行完成
            plan.markCompleted();
            stateManager.savePlan(plan);

            log.info("[WorkerNode] All layers completed: sessionId={}, totalLayers={}",
                    sessionId, maxLayer + 1);
            eventBus.publish(sessionId, new SessionCompleteEvent(
                    sessionId,
                    "所有步骤已成功完成",
                    plan.getSteps().size()
            ));

            return Map.of("nextAction", "complete");
        }

        // 获取当前层的步骤
        List<PlanStep> layerSteps = layerMap.getOrDefault(currentLayerIndex, List.of());

        if (layerSteps.isEmpty()) {
            // 当前层没有步骤，跳到下一层
            log.warn("[WorkerNode] Layer {} is empty, skipping to next layer", currentLayerIndex);
            return Map.of(
                    "nextAction", "continue",
                    "currentLayerIndex", currentLayerIndex + 1
            );
        }

        log.info("[WorkerNode] Executing {} steps in layer {}", layerSteps.size(), currentLayerIndex);

        // 发布层级开始事件
        List<String> stepIds = layerSteps.stream().map(PlanStep::getStepId).toList();
        eventBus.publish(sessionId, new LayerStartEvent(sessionId, currentLayerIndex, stepIds, layerSteps.size()));

        // 创建 ParallelExecutor 并执行当前层
        ParallelExecutor executor = new ParallelExecutor(
                stateManager, eventBus, contextService, contextAssembler,
                sessionStopService, executionTracker, reactAgentFactory, promptManager
        );

        Map<String, StepResult> results;
        try {
            results = executor.executeLayer(sessionId, layerSteps);
        } catch (SessionStoppedException e) {
            log.info("[WorkerNode] Session stopped during layer execution: sessionId={}, layer={}",
                    sessionId, currentLayerIndex);
            eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "USER_STOP"));
            return Map.of("nextAction", "complete");
        }

        // 保存计划状态
        stateManager.savePlan(plan);

        // 统计成功和失败数量
        long successCount = results.values().stream().filter(StepResult::isSuccess).count();
        long failedCount = results.values().stream().filter(StepResult::isBlocked).count();

        // 发布层级完成事件
        eventBus.publish(sessionId, new LayerCompleteEvent(sessionId, currentLayerIndex, (int) successCount, (int) failedCount));

        // 检查是否有步骤被阻塞或需要用户输入
        for (Map.Entry<String, StepResult> entry : results.entrySet()) {
            StepResult result = entry.getValue();

            if (result.isBlocked()) {
                log.warn("[WorkerNode] Step {} is blocked: {}", entry.getKey(), result.getOutput());
                return Map.of(
                        "nextAction", "replan",
                        "currentLayerIndex", currentLayerIndex
                );
            }

            if (result.needsUserInput()) {
                log.info("[WorkerNode] Step {} needs user input: {}", entry.getKey(), result.getQuestion());
                return Map.of(
                        "nextAction", "clarify",
                        "clarifyQuestion", result.getQuestion(),
                        "currentLayerIndex", currentLayerIndex
                );
            }
        }

        // 继续下一层
        int nextLayerIndex = currentLayerIndex + 1;
        if (nextLayerIndex > maxLayer) {
            // 所有层级执行完成
            plan.markCompleted();
            stateManager.savePlan(plan);

            log.info("[WorkerNode] All layers completed: sessionId={}, totalLayers={}",
                    sessionId, maxLayer + 1);
            eventBus.publish(sessionId, new SessionCompleteEvent(
                    sessionId,
                    "所有步骤已成功完成",
                    plan.getSteps().size()
            ));

            return Map.of("nextAction", "complete");
        }

        return Map.of(
                "nextAction", "continue",
                "currentLayerIndex", nextLayerIndex
        );
    }
}
