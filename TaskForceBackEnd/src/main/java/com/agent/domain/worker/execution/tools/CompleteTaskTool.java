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
 * 完成任务工具
 * Worker 使用此工具标记任务为完成状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompleteTaskTool implements ToolCallback {

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
                  "description": "要完成的任务序号，如 1"
                },
                "summary": {
                  "type": "string",
                  "description": "任务完成总结（可选）"
                }
              },
              "required": ["taskId"]
            }
            """;

        return ToolDefinition.builder()
                .name("complete_task")
                .description("标记任务为完成状态")
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
            int taskId = ((Number) args.get("taskId")).intValue();
            String summary = (String) args.get("summary");

            taskBoardService.completeTask(sessionId, taskId);

            log.info("[CompleteTaskTool] Task completed: taskId={}", taskId);

            if (summary != null && !summary.isEmpty()) {
                return String.format("Task #%d completed successfully. Summary: %s", taskId, summary);
            } else {
                return String.format("Task #%d completed successfully", taskId);
            }

        } catch (Exception e) {
            log.error("[CompleteTaskTool] Error completing task", e);
            return "Error completing task: " + e.getMessage();
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
