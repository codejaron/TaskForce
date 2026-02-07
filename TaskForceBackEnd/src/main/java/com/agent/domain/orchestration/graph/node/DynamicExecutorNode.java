package com.agent.domain.orchestration.graph.node;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.service.ContextService;
import com.agent.domain.orchestration.graph.topology.TopologySort;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
import com.agent.domain.orchestration.model.StepStatus;
import com.agent.domain.orchestration.state.StateManager;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.LayerCompleteEvent;
import com.agent.infrastructure.event.events.LayerStartEvent;
import com.agent.infrastructure.event.events.SessionCompleteEvent;
import com.agent.infrastructure.event.events.SessionPauseEvent;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.service.SessionStopService;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 动态执行器节点
 * 负责动态编译子 Graph 并一次性执行所有步骤
 */
@Slf4j
@RequiredArgsConstructor
public class DynamicExecutorNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final ContextService contextService;
    private final ContextAssembler contextAssembler;
    private final SessionStopService sessionStopService;
    private final ReactAgentFactory reactAgentFactory;
    private final PromptManager promptManager;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");

        log.info("[DynamicExecutorNode] Starting dynamic execution: sessionId={}", sessionId);

        // 检查是否需要停止
        if (sessionStopService.shouldStop(sessionId)) {
            log.info("[DynamicExecutorNode] Session stopped by user: sessionId={}", sessionId);
            eventBus.publish(sessionId, new SessionPauseEvent(sessionId, "USER_STOP"));
            return Map.of("nextAction", "complete");
        }

        // 加载计划
        ExecutionPlan plan = stateManager.loadPlan(sessionId);
        List<PlanStep> steps = plan.getSteps();

        if (steps == null || steps.isEmpty()) {
            log.warn("[DynamicExecutorNode] No steps to execute: sessionId={}", sessionId);
            return Map.of("nextAction", "complete");
        }

        try {
            // 1. 拓扑排序，得到分层结构
            List<List<PlanStep>> layers = TopologySort.sort(steps);

            log.info("[DynamicExecutorNode] Building sub-graph with {} layers", layers.size());

            // 2. 动态构建子 Graph
            StateGraph subGraph = buildSubGraph(sessionId, layers);

            // 3. 编译子 Graph
            CompiledGraph compiled = subGraph.compile();

            // 4. 执行子 Graph
            Map<String, Object> initialState = new HashMap<>();
            initialState.put("sessionId", sessionId);

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId + "_subgraph")
                    .build();

            log.info("[DynamicExecutorNode] Executing compiled sub-graph");

            // 同步执行子 Graph
            compiled.invoke(initialState, config);

            // 5. 检查执行结果
            return analyzeExecutionResult(sessionId, plan);

        } catch (Exception e) {
            log.error("[DynamicExecutorNode] Failed to execute sub-graph: sessionId={}", sessionId, e);
            return Map.of(
                    "nextAction", "replan",
                    "error", e.getMessage()
            );
        }
    }

    /**
     * 构建子 Graph
     */
    private StateGraph buildSubGraph(String sessionId, List<List<PlanStep>> layers) throws GraphStateException {
        // 定义子 Graph 的状态字段
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("sessionId", new ReplaceStrategy());
            strategies.put("layerIndex", new ReplaceStrategy());

            // 为每个步骤的结果添加状态字段
            for (List<PlanStep> layer : layers) {
                for (PlanStep step : layer) {
                    strategies.put("step_result_" + step.getStepId(), new ReplaceStrategy());
                }
            }

            return strategies;
        };

        StateGraph subGraph = new StateGraph("worker-subgraph", keyStrategyFactory);

        // 为每个步骤添加 Worker 节点
        for (List<PlanStep> layer : layers) {
            for (PlanStep step : layer) {
                String nodeId = "worker_" + step.getStepIndex();
                WorkerReactNode workerNode = new WorkerReactNode(
                        stateManager, eventBus, contextService, contextAssembler,
                        sessionStopService, reactAgentFactory, promptManager, step
                );
                subGraph.addNode(nodeId, node_async(workerNode));
            }
        }

        // 构建层级连接
        String prevNode = START;

        for (int i = 0; i < layers.size(); i++) {
            List<PlanStep> layer = layers.get(i);
            List<String> nodeIds = layer.stream()
                    .map(s -> "worker_" + s.getStepIndex())
                    .toList();

            // 注意：层级开始事件会在实际执行时由 WorkerReactNode 或 DispatchNode 触发
            // 这里不发布，避免重复

            if (nodeIds.size() == 1) {
                // 单节点层：直接连接
                subGraph.addEdge(prevNode, nodeIds.get(0));
                prevNode = nodeIds.get(0);

                // 单节点也需要发布层级开始事件
                List<String> stepIds = layer.stream().map(PlanStep::getStepId).toList();
                eventBus.publish(sessionId, new LayerStartEvent(sessionId, i, stepIds, 1));
            } else {
                // 多节点层：插入 dispatch 和 aggregator
                String dispatchId = "dispatch_" + i;
                String aggId = "agg_" + i;

                List<String> stepIds = layer.stream().map(PlanStep::getStepId).toList();

                // 添加 dispatch 节点（pass-through + 发布层级开始事件）
                subGraph.addNode(dispatchId, node_async(new DispatchNode(i, stepIds, eventBus)));
                subGraph.addEdge(prevNode, dispatchId);

                // dispatch -> 并行 workers
                for (String nodeId : nodeIds) {
                    subGraph.addEdge(dispatchId, nodeId);
                }

                // 添加 aggregator 节点（收集结果 + 发布层级完成事件）
                subGraph.addNode(aggId, node_async(new LayerAggregatorNode(i, eventBus)));

                // 并行 workers -> aggregator
                for (String nodeId : nodeIds) {
                    subGraph.addEdge(nodeId, aggId);
                }

                prevNode = aggId;
            }

            // 发布层级完成事件（这里是预期的，实际完成会在 aggregator 中发布）
            // 注意：这里只是占位，实际的完成事件应该在执行后发布
        }

        // 连接到 END
        subGraph.addEdge(prevNode, END);

        log.info("[DynamicExecutorNode] Sub-graph built successfully with {} layers", layers.size());

        return subGraph;
    }

    /**
     * 分析执行结果
     */
    private Map<String, Object> analyzeExecutionResult(String sessionId, ExecutionPlan plan) {
        // 重新加载计划以获取最新状态
        plan = stateManager.loadPlan(sessionId);

        // 统计步骤状态
        Map<StepStatus, Long> statusCount = plan.getSteps().stream()
                .collect(Collectors.groupingBy(PlanStep::getStatus, Collectors.counting()));

        long doneCount = statusCount.getOrDefault(StepStatus.DONE, 0L);
        long blockedCount = statusCount.getOrDefault(StepStatus.BLOCKED, 0L);
        long pendingCount = statusCount.getOrDefault(StepStatus.PENDING, 0L);

        log.info("[DynamicExecutorNode] Execution result: done={}, blocked={}, pending={}",
                doneCount, blockedCount, pendingCount);

        // 检查是否有被阻塞的步骤
        if (blockedCount > 0) {
            log.warn("[DynamicExecutorNode] {} steps are blocked, triggering replan", blockedCount);

            // 找到被阻塞的步骤
            List<PlanStep> blockedSteps = plan.getSteps().stream()
                    .filter(s -> s.getStatus() == StepStatus.BLOCKED)
                    .toList();

            // 标记依赖被阻塞步骤的其他步骤也为 BLOCKED
            markDependentStepsAsBlocked(plan, blockedSteps);

            stateManager.savePlan(plan);

            return Map.of(
                    "nextAction", "replan",
                    "blockedCount", blockedCount
            );
        }

        // 检查是否需要用户输入
        if (pendingCount > 0) {
            log.info("[DynamicExecutorNode] {} steps need user input", pendingCount);

            PlanStep pendingStep = plan.getSteps().stream()
                    .filter(s -> s.getStatus() == StepStatus.PENDING)
                    .findFirst()
                    .orElse(null);

            if (pendingStep != null) {
                return Map.of(
                        "nextAction", "clarify",
                        "clarifyQuestion", "需要用户输入"
                );
            }
        }

        // 所有步骤完成
        plan.markCompleted();
        stateManager.savePlan(plan);

        log.info("[DynamicExecutorNode] All steps completed successfully: sessionId={}", sessionId);
        eventBus.publish(sessionId, new SessionCompleteEvent(
                sessionId,
                "所有步骤已成功完成",
                plan.getSteps().size()
        ));

        return Map.of("nextAction", "complete");
    }

    /**
     * 标记依赖被阻塞步骤的其他步骤也为 BLOCKED
     */
    private void markDependentStepsAsBlocked(ExecutionPlan plan, List<PlanStep> blockedSteps) {
        Set<String> blockedIds = blockedSteps.stream()
                .map(PlanStep::getStepId)
                .collect(Collectors.toSet());

        boolean changed = true;
        while (changed) {
            changed = false;
            for (PlanStep step : plan.getSteps()) {
                if (step.getStatus() != StepStatus.BLOCKED) {
                    List<String> dependsOn = step.getDependsOn();
                    if (dependsOn != null && !dependsOn.isEmpty()) {
                        // 检查是否依赖被阻塞的步骤
                        boolean dependsOnBlocked = dependsOn.stream()
                                .anyMatch(blockedIds::contains);

                        if (dependsOnBlocked) {
                            step.setStatus(StepStatus.BLOCKED);
                            blockedIds.add(step.getStepId());
                            changed = true;
                            log.info("[DynamicExecutorNode] Marking step {} as BLOCKED due to dependency",
                                    step.getStepIndex());
                        }
                    }
                }
            }
        }
    }
}
