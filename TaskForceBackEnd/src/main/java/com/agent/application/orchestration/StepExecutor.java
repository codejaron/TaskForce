package com.agent.application.orchestration;

import com.agent.domain.model.context.TaskContext;
import com.agent.domain.model.plan.PlanStep;
import com.agent.domain.model.plan.StepResult;
import com.agent.infrastructure.context.SessionContextHolder;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.WorkerDeltaEvent;
import com.agent.infrastructure.llm.LlmAdapter;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.model.AgentProfile;
import com.agent.util.ArtifactParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 步骤执行器
 * 负责执行单个计划步骤
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepExecutor {

    private final LlmAdapter llmAdapter;
    private final StateManager stateManager;
    private final EventBus eventBus;
    private final PromptManager promptManager;

    /**
     * 执行步骤
     */
    public StepResult execute(String sessionId, PlanStep step) {
        log.info("[StepExecutor] Executing step: sessionId={}, stepId={}, stepIndex={}",
                sessionId, step.getStepId(), step.getStepIndex());

        try {
            // 设置 ThreadLocal 上下文
            SessionContextHolder.setSessionId(sessionId);
            log.debug("[StepExecutor] SessionContext set: sessionId={}", sessionId);

            // 1. 加载 Worker 配置
            AgentProfile worker = stateManager.loadAgent(step.getAssignedAgentId());
            if (worker == null) {
                log.error("[StepExecutor] Worker not found: {}", step.getAssignedAgentId());
                return StepResult.blocked("Worker not found: " + step.getAssignedAgentId());
            }

            // 2. 构建上下文（使用新方法）
            TaskContext context = stateManager.buildContext(sessionId);

            // 3. 构建 Prompt（传入 TaskContext）
            String systemPrompt = promptManager.buildWorkerPromptWithContext(context, worker);
            log.debug("[StepExecutor]  Worker '{}' (步骤 {}) 收到的 Prompt:\n{}",
                    worker.getName(), step.getStepId(), systemPrompt);

            StringBuilder response = new StringBuilder();

            // 4. 流式调用 Worker
            llmAdapter.streamChat(Long.valueOf(step.getAssignedAgentId()), sessionId, systemPrompt, null)
                    .doOnNext(token -> {
                        response.append(token);
                        eventBus.publish(sessionId, new WorkerDeltaEvent(sessionId, step.getStepId(), token));
                    })
                    .blockLast();

            String output = response.toString();
            log.debug("[StepExecutor]  Worker '{}' 完整响应 ({}字符):\n{}",
                    worker.getName(), output.length(), output);

            // 5. 提取并存储 Artifact（程序用）
            List<ArtifactParser.Artifact> artifacts = ArtifactParser.extract(output);
            if (!artifacts.isEmpty()) {
                log.info("[StepExecutor] Extracted {} artifact(s) from worker '{}'",
                        artifacts.size(), worker.getName());
                for (ArtifactParser.Artifact artifact : artifacts) {
                    log.debug("[StepExecutor]  Saving artifact: key='{}', valueLength={}",
                            artifact.getKey(), artifact.getValue().length());
                    stateManager.saveArtifact(sessionId, artifact.getKey(), artifact.getValue());
                }
            }

            // 6. 存储完整消息（包含 artifact 标签，前端解析）
            stateManager.recordStepMessage(sessionId, step, output);

            // 7. 解析结果状态
            return parseStepResult(output);

        } catch (Exception e) {
            log.error("[StepExecutor] Failed to execute step", e);
            return StepResult.blocked("执行失败: " + e.getMessage());
        } finally {
            // 清理 ThreadLocal，避免内存泄漏
            SessionContextHolder.clear();
            log.debug("[StepExecutor] SessionContext cleared");
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
