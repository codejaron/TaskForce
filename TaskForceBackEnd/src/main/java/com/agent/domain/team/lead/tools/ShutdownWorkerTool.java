package com.agent.domain.team.lead.tools;

import com.agent.domain.worker.service.WorkerInstanceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 关闭 Worker 工具
 * Lead 使用此工具关闭指定的 Worker 实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShutdownWorkerTool implements ToolCallback {

    private final WorkerInstanceManager workerInstanceManager;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "workerId": {
                  "type": "integer",
                  "description": "要关闭的 Worker ID（会话内数字编号）"
                }
              },
              "required": ["workerId"]
            }
            """;

        return ToolDefinition.builder()
                .name("shutdown_worker")
                .description("关闭指定的 Worker 实例")
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            Map<String, Object> args = objectMapper.readValue(toolInput, Map.class);
            String sessionId = extractSessionId(toolContext);
            int workerId = ((Number) args.get("workerId")).intValue();

            var worker = workerInstanceManager.findBySessionAndWorkerId(sessionId, workerId).orElse(null);
            if (worker == null || worker.isShutdown()) {
                return String.format("Failed to shutdown worker #%d (not found or already shutdown)", workerId);
            }

            boolean success = workerInstanceManager.shutdown(worker.getInstanceId());

            if (success) {
                log.info("[ShutdownWorkerTool] Shutdown worker: sessionId={}, workerId={}, instanceId={}",
                        sessionId, workerId, worker.getInstanceId());
                return String.format("Worker #%d shutdown successfully", workerId);
            } else {
                log.warn("[ShutdownWorkerTool] Failed to shutdown worker: sessionId={}, workerId={}, instanceId={}",
                        sessionId, workerId, worker.getInstanceId());
                return String.format("Failed to shutdown worker #%d (loop not found)", workerId);
            }

        } catch (Exception e) {
            log.error("[ShutdownWorkerTool] Error shutting down worker", e);
            return "Error shutting down worker: " + e.getMessage();
        }
    }

    private String extractSessionId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object sessionId = toolContext.getContext().get("sessionId");
            if (sessionId != null) {
                return sessionId.toString();
            }
        }
        throw new IllegalArgumentException("sessionId not found in tool context");
    }
}
