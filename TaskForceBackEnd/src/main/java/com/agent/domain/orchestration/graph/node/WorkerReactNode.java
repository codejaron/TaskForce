package com.agent.domain.orchestration.graph.node;

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
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.service.SessionStopService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Worker ReactAgent 节点
 * 负责执行单个步骤
 */
@Slf4j
@RequiredArgsConstructor
public class WorkerReactNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final ContextService contextService;
    private final ContextAssembler contextAssembler;
    private final SessionStopService sessionStopService;
    private final ReactAgentFactory reactAgentFactory;
    private final PromptManager promptManager;
    private final PlanStep step;

    private static final int MAX_REACT_ITERATIONS = 20;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        int layerIndex = state.value("layerIndex", 0);

        log.info("[WorkerReactNode] Executing step: sessionId={}, stepId={}, stepIndex={}",
                sessionId, step.getStepId(), step.getStepIndex());

        // 检查是否需要停止
        if (sessionStopService.shouldStop(sessionId)) {
            log.info("[WorkerReactNode] Session stopped: sessionId={}, stepId={}",
                    sessionId, step.getStepId());
            return Map.of(
                    "stepId", step.getStepId(),
                    "stepIndex", step.getStepIndex(),
                    "status", StepStatus.BLOCKED.name(),
                    "output", "Session stopped",
                    "layerIndex", layerIndex
            );
        }

        // 发布开始事件
        eventBus.publish(sessionId, new StepStartEvent(
                sessionId,
                step.getStepId(),
                step.getStepIndex(),
                step.getInstruction(),
                step.getAssignedAgentId(),
                step.getAssignedAgentName()
        ));

        try {
            // 1. 加载 Worker 配置
            Agent worker = stateManager.loadAgent(step.getAssignedAgentId());
            if (worker == null) {
                log.error("[WorkerReactNode] Worker not found: {}", step.getAssignedAgentId());
                return buildResult(sessionId, layerIndex, StepStatus.BLOCKED, "Worker not found: " + step.getAssignedAgentId());
            }

            // 2. 组装上下文
            ExecutionPlan plan = stateManager.loadPlan(sessionId);
            String assembledContext = contextAssembler.assemble(plan, step.getStepIndex());


            // 3. 构建完整的 Worker Prompt
            String fullInstruction = promptManager.buildWorkerPromptWithAssembledContext(
                    assembledContext, worker, step);

            // 4. 构建 ReactAgent
            ReactAgent reactAgent = reactAgentFactory.buildWorkerReactAgent(
                    Long.valueOf(step.getAssignedAgentId()),
                    fullInstruction,
                    MAX_REACT_ITERATIONS,
                    sessionId,
                    step.getStepId(),
                    step.getStepIndex()
            );

            // 5. 创建流式消息记录
            Long messageId = stateManager.createStreamingMessage(sessionId, step);

            // 6. 配置 RunnableConfig
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId + "_" + step.getStepId())
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
            StepResult result = parseStepResult(output);

            // 9. 更新步骤状态
            StepStatus status;
            if (result.isSuccess()) {
                status = StepStatus.DONE;
                step.setStatus(StepStatus.DONE);
            } else if (result.isBlocked()) {
                status = StepStatus.BLOCKED;
                step.setStatus(StepStatus.BLOCKED);
            } else if (result.needsUserInput()) {
                status = StepStatus.PENDING;
                step.setStatus(StepStatus.PENDING);
            } else {
                status = StepStatus.DONE;
                step.setStatus(StepStatus.DONE);
            }

            // 10. 发布完成事件
            eventBus.publish(sessionId, new StepCompletedEvent(
                    sessionId,
                    step.getStepId(),
                    step.getStepIndex(),
                    output
            ));

            return buildResult(sessionId, layerIndex, status, output);

        } catch (Exception e) {
            log.error("[WorkerReactNode] Failed to execute step: stepId={}", step.getStepId(), e);
            step.setStatus(StepStatus.BLOCKED);
            return buildResult(sessionId, layerIndex, StepStatus.BLOCKED, "执行失败: " + e.getMessage());
        }
    }

    private Map<String, Object> buildResult(String sessionId, int layerIndex, StepStatus status, String output) {
        // 构建步骤结果
        Map<String, Object> stepResult = new HashMap<>();
        stepResult.put("stepId", step.getStepId());
        stepResult.put("stepIndex", step.getStepIndex());
        stepResult.put("status", status.name());
        stepResult.put("output", output);
        stepResult.put("layerIndex", layerIndex);

        // 将结果写入 state，使用 "step_result_{stepId}" 作为 key
        Map<String, Object> result = new HashMap<>();
        result.put("step_result_" + step.getStepId(), stepResult);
        result.put("layerIndex", layerIndex);

        return result;
    }

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
