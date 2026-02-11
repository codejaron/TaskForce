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
                "instanceId": {
                  "type": "string",
                  "description": "Worker 实例 ID"
                }
              },
              "required": ["instanceId"]
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
            String instanceId = (String) args.get("instanceId");

            boolean success = workerInstanceManager.shutdown(instanceId);

            if (success) {
                log.info("[ShutdownWorkerTool] Shutdown worker: instanceId={}", instanceId);
                return String.format("Worker shutdown successfully: instanceId=%s", instanceId);
            } else {
                log.warn("[ShutdownWorkerTool] Failed to shutdown worker: instanceId={}", instanceId);
                return String.format("Failed to shutdown worker: instanceId=%s (not found or already shutdown)",
                        instanceId);
            }

        } catch (Exception e) {
            log.error("[ShutdownWorkerTool] Error shutting down worker", e);
            return "Error shutting down worker: " + e.getMessage();
        }
    }
}
