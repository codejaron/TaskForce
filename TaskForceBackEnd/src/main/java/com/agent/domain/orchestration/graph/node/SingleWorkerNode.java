package com.agent.domain.orchestration.graph.node;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.service.ContextService;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.domain.orchestration.model.PlanStep;
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
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个 Worker 节点
 * 用于子 Graph 中执行单个步骤（使用 ReactAgent）
 */
@Slf4j
public class SingleWorkerNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final ContextService contextService;
    private final ContextAssembler contextAssembler;
    private final SessionStopService sessionStopService;
    private final ReactAgentFactory reactAgentFactory;
    private final PromptManager promptManager;
    private final PlanStep step;
    private final String sessionId;

    private static final int MAX_REACT_ITERATIONS = 20;

    public SingleWorkerNode(
            StateManager stateManager,
            EventBus eventBus,
            ContextService contextService,
            ContextAssembler contextAssembler,
            SessionStopService sessionStopService,
            ReactAgentFactory reactAgentFactory,
            PromptManager promptManager,
            PlanStep step,
            String sessionId) {
        this.stateManager = stateManager;
        this.eventBus = eventBus;
        this.contextService = contextService;
        this.contextAssembler = contextAssembler;
        this.sessionStopService = sessionStopService;
        this.reactAgentFactory = reactAgentFactory;
        this.promptManager = promptManager;
        this.step = step;
        this.sessionId = sessionId;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String outputKey = "step_" + step.getStepIndex() + "_output";
        String statusKey = "step_" + step.getStepIndex() + "_status";

        try {
            // 检查是否需要停止
            if (sessionStopService.shouldStop(sessionId)) {
                log.info("[SingleWorkerNode] Session stopped: sessionId={}, stepId={}", sessionId, step.getStepId());
                Map<String, Object> result = new HashMap<>();
                result.put(outputKey, "Session stopped");
                result.put(statusKey, "BLOCKED");
                return result;
            }

            log.info("[SingleWorkerNode] Executing step: stepId={}, instruction={}", step.getStepId(), step.getInstruction());

            // 发布开始事件
            eventBus.publish(sessionId, new StepStartEvent(
                    sessionId,
                    step.getStepId(),
                    step.getStepIndex(),
                    step.getInstruction(),
                    step.getAssignedAgentId(),
                    step.getAssignedAgentName()
            ));

            // 1. 加载 Worker 配置
            Agent worker = stateManager.loadAgent(step.getAssignedAgentId());
            if (worker == null) {
                log.error("[SingleWorkerNode] Worker not found: {}", step.getAssignedAgentId());
                Map<String, Object> result = new HashMap<>();
                result.put(outputKey, "Worker not found: " + step.getAssignedAgentId());
                result.put(statusKey, "BLOCKED");
                return result;
            }

            // 2. 组装上下文
            ExecutionPlan plan = stateManager.loadPlan(sessionId);
            String assembledContext = contextAssembler.assemble(plan, step.getStepIndex());

            // 3. 使用 PromptManager 构建完整的 Worker Prompt
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
            String status = parseStepStatus(output);

            // 9. 更新步骤状态
            if ("DONE".equals(status)) {
                step.setStatus(StepStatus.DONE);
                stateManager.updateStepStatus(sessionId, step.getStepId(), StepStatus.DONE, null);
            } else if ("BLOCKED".equals(status)) {
                String reason = extractAfterMarker(output, "BLOCKED:");
                step.setStatus(StepStatus.BLOCKED);
                step.setBlockedReason(reason);
                stateManager.updateStepStatus(sessionId, step.getStepId(), StepStatus.BLOCKED, reason);
            }

            // 发布完成事件
            eventBus.publish(sessionId, new StepCompletedEvent(
                    sessionId,
                    step.getStepId(),
                    step.getStepIndex(),
                    output
            ));

            // 返回结果到 state
            Map<String, Object> result = new HashMap<>();
            result.put(outputKey, output);
            result.put(statusKey, status);
            return result;

        } catch (Exception e) {
            log.error("[SingleWorkerNode] Failed to execute step: stepId={}", step.getStepId(), e);
            Map<String, Object> result = new HashMap<>();
            result.put(outputKey, "执行失败: " + e.getMessage());
            result.put(statusKey, "BLOCKED");
            return result;
        }
    }

    /**
     * 解析步骤状态
     */
    private String parseStepStatus(String response) {
        if (response.contains("BLOCKED:")) {
            return "BLOCKED";
        }
        if (response.contains("NEED_USER_INPUT:")) {
            return "NEED_USER_INPUT";
        }
        return "DONE";
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
