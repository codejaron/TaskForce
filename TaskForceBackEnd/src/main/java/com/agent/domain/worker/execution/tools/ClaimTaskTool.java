package com.agent.domain.worker.execution.tools;

import com.agent.domain.taskboard.service.TaskBoardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 认领任务工具
 * Worker 使用此工具认领指定的任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimTaskTool implements ToolCallback {

    private final TaskBoardService taskBoardService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "taskId": {
                  "type": "integer",
                  "description": "要认领的任务序号，如 1"
                }
              },
              "required": ["taskId"]
            }
            """;

        return ToolDefinition.builder()
                .name("claim_task")
                .description("认领指定的任务")
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
            String instanceId = extractInstanceId(toolContext);
            int taskId = ((Number) args.get("taskId")).intValue();

            boolean claimed = taskBoardService.claimTask(sessionId, taskId, instanceId);

            if (claimed) {
                log.info("[ClaimTaskTool] Task claimed successfully: taskId={}, instanceId={}", taskId, instanceId);
                return String.format("Task #%d claimed successfully", taskId);
            } else {
                log.warn("[ClaimTaskTool] Failed to claim task: taskId={}, instanceId={}", taskId, instanceId);
                return String.format("Failed to claim task #%d (already claimed or invalid status)", taskId);
            }

        } catch (Exception e) {
            log.error("[ClaimTaskTool] Error claiming task", e);
            return "Error claiming task: " + e.getMessage();
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

    private String extractInstanceId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object instanceId = toolContext.getContext().get("instanceId");
            if (instanceId != null) {
                return instanceId.toString();
            }
        }
        throw new IllegalArgumentException("instanceId not found in tool context");
    }
}
