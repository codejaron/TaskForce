package com.agent.infrastructure.graph.node;

import com.agent.application.orchestration.StateManager;
import com.agent.domain.model.context.TaskContext;
import com.agent.domain.model.plan.ExecutionPlan;
import com.agent.domain.model.plan.PlanStep;
import com.agent.domain.model.plan.StepResult;
import com.agent.domain.model.plan.StepStatus;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.model.AgentProfile;
import com.agent.util.ArtifactParser;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Worker Node（从 StepExecutor 改造）
 * 负责执行单个计划步骤
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final LlmAdapter llmAdapter;
    private final PromptManager promptManager;
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        int stepIndex = state.value("currentStepIndex", 0);
        
        log.info("[WorkerNode] Executing step {}: sessionId={}", stepIndex, sessionId);
        
        // 加载计划和当前步骤
        ExecutionPlan plan = stateManager.loadPlan(sessionId);
        
        // 检查是否还有步骤
        if (stepIndex >= plan.getSteps().size()) {
            // 标记计划完成并发布事件
            plan.markCompleted();
            stateManager.savePlan(plan);
            
            log.info("[WorkerNode] All steps completed: sessionId={}, totalSteps={}", 
                    sessionId, plan.getSteps().size());
            eventBus.publish(sessionId, new SessionCompleteEvent(
                sessionId,
                "所有步骤已成功完成",
                plan.getSteps().size()
            ));
            
            return Map.of("nextAction", "complete");
        }
        
        PlanStep step = plan.getSteps().get(stepIndex);
        log.info("[WorkerNode] Executing step: stepId={}, description={}", 
                step.getStepId(), step.getDescription());
        
        // 发布事件
        eventBus.publish(sessionId, new StepStartEvent(
                sessionId,
                step.getStepId(),
                step.getStepIndex(),
                step.getDescription(),
                step.getAssignedAgentId(),
                step.getAssignedAgentName()
        ));
        
        // 执行步骤
        StepResult result = executeStep(sessionId, step);
        
        // 更新步骤状态
        if (result.isSuccess()) {
            step.setStatus(StepStatus.DONE);
        } else if (result.isBlocked()) {
            step.setStatus(StepStatus.BLOCKED);
        }
        stateManager.savePlan(plan);
        
        // 发布完成事件
        eventBus.publish(sessionId, new StepCompletedEvent(
                sessionId,
                step.getStepId(),
                step.getStepIndex(),
                result.getOutput()
        ));
        
        // 决定下一步
        if (result.isBlocked()) {
            return Map.of(
                "nextAction", "replan",
                "currentStepIndex", stepIndex
            );
        }
        
        if (result.needsUserInput()) {
            return Map.of(
                "nextAction", "clarify",
                "clarifyQuestion", result.getQuestion(),
                "currentStepIndex", stepIndex
            );
        }
        
        // 继续下一步
        int nextIndex = stepIndex + 1;
        if (nextIndex >= plan.getSteps().size()) {
            // 标记计划完成并发布事件
            plan.markCompleted();
            stateManager.savePlan(plan);
            
            log.info("[WorkerNode] All steps completed: sessionId={}, totalSteps={}", 
                    sessionId, plan.getSteps().size());
            eventBus.publish(sessionId, new SessionCompleteEvent(
                sessionId,
                "所有步骤已成功完成",
                plan.getSteps().size()
            ));
            
            return Map.of("nextAction", "complete");
        }
        
        return Map.of(
            "nextAction", "continue",
            "currentStepIndex", nextIndex
        );
    }
    
    /**
     * 执行步骤（简化版本，使用现有的 streamChat）
     */
    private StepResult executeStep(String sessionId, PlanStep step) {
        try {
            // 1. 加载 Worker 配置
            AgentProfile worker = stateManager.loadAgent(step.getAssignedAgentId());
            if (worker == null) {
                log.error("[WorkerNode] Worker not found: {}", step.getAssignedAgentId());
                return StepResult.blocked("Worker not found: " + step.getAssignedAgentId());
            }
            
            // 2. 构建上下文
            TaskContext context = stateManager.buildContext(sessionId);
            
            // 3. 构建 Prompt
            String systemPrompt = promptManager.buildWorkerPromptWithContext(context, worker);
            log.debug("[WorkerNode] Worker '{}' (步骤 {}) 收到的 Prompt:\n{}",
                    worker.getName(), step.getStepId(), systemPrompt);
            
            StringBuilder response = new StringBuilder();
            StringBuilder buffer = new StringBuilder();
            final int FLUSH_THRESHOLD = 100;
            
            // 4. 创建流式消息记录
            Long messageId = stateManager.createStreamingMessage(sessionId, step);
            
            // 5. 流式调用，边输出边持久化
            llmAdapter.streamChat(Long.valueOf(step.getAssignedAgentId()), sessionId, step.getStepId(), systemPrompt, null)
                    .doOnNext(token -> {
                        response.append(token);
                        buffer.append(token);
                        eventBus.publish(sessionId, new WorkerDeltaEvent(sessionId, step.getStepId(), token));
                        
                        if (buffer.length() >= FLUSH_THRESHOLD) {
                            stateManager.appendStreamingContent(messageId, buffer.toString());
                            buffer.setLength(0);
                        }
                    })
                    .doOnComplete(() -> {
                        if (buffer.length() > 0) {
                            stateManager.appendStreamingContent(messageId, buffer.toString());
                        }
                        stateManager.completeStreamingMessage(messageId, response.toString());
                    })
                    .doOnError(e -> {
                        if (buffer.length() > 0) {
                            stateManager.appendStreamingContent(messageId, buffer.toString());
                        }
                        stateManager.completeStreamingMessage(messageId, response.toString());
                    })
                    .blockLast();
            
            String output = response.toString();
            log.debug("[WorkerNode] Worker '{}' 完整响应 ({}字符):\n{}",
                    worker.getName(), output.length(), output);
            
            // 5. 提取并存储 Artifact
            List<ArtifactParser.Artifact> artifacts = ArtifactParser.extract(output);
            if (!artifacts.isEmpty()) {
                log.info("[WorkerNode] Extracted {} artifact(s) from worker '{}'",
                        artifacts.size(), worker.getName());
                for (ArtifactParser.Artifact artifact : artifacts) {
                    log.debug("[WorkerNode] Saving artifact: key='{}', valueLength={}",
                            artifact.getKey(), artifact.getValue().length());
                    stateManager.saveArtifact(sessionId, artifact.getKey(), artifact.getValue());
                }
            }
            
            // 6. 解析结果状态（删除 recordStepMessage，因为已经在流式输出中处理）
            return parseStepResult(output);
            
        } catch (Exception e) {
            log.error("[WorkerNode] Failed to execute step", e);
            return StepResult.blocked("执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析步骤结果
     */
    private StepResult parseStepResult(String response) {
        // 检查是否阻塞
        if (response.contains("BLOCKED:")) {
            String reason = extractAfterMarker(response, "BLOCKED:");
            return StepResult.blocked(reason);
        }
        
        // 检查是否需要用户输入
        if (response.contains("NEED_USER_INPUT:")) {
            String question = extractAfterMarker(response, "NEED_USER_INPUT:");
            return StepResult.needsUserInput(question);
        }
        
        // 正常完成
        return StepResult.success(response);
    }
    
    /**
     * 提取标记后的内容
     */
    private String extractAfterMarker(String response, String marker) {
        int idx = response.indexOf(marker);
        if (idx >= 0) {
            String after = response.substring(idx + marker.length()).trim();
            // 取第一行
            int newline = after.indexOf("\n");
            if (newline > 0) {
                return after.substring(0, newline).trim();
            }
            return after;
        }
        return response;
    }
}
