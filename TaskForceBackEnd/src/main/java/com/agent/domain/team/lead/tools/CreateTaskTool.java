package com.agent.domain.team.lead.tools;

import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 创建任务工具
 * Lead 使用此工具创建新任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateTaskTool implements ToolCallback {

    private final TaskBoardService taskBoardService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "subject": {
                  "type": "string",
                  "description": "任务标题"
                },
                "description": {
                  "type": "string",
                  "description": "任务详细描述"
                },
                "blockedBy": {
                  "type": "array",
                  "items": {
                    "type": "integer"
                  },
                  "description": "依赖的任务序号列表，如 [1, 2]（可选）"
                }
              },
              "required": ["subject", "description"]
            }
            """;

        return ToolDefinition.builder()
                .name("create_task")
                .description("创建新任务并添加到任务板")
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
            String subject = (String) args.get("subject");
            String description = (String) args.get("description");

            @SuppressWarnings("unchecked")
            List<Integer> blockedBy = args.containsKey("blockedBy")
                ? ((List<?>) args.get("blockedBy")).stream()
                    .map(v -> v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(v.toString()))
                    .collect(java.util.stream.Collectors.toList())
                : List.of();

            Task task = taskBoardService.createTask(sessionId, subject, description, blockedBy);

            log.info("[CreateTaskTool] Created task: taskId={}, subject={}", task.getTaskId(), subject);

            return String.format("Task #%d created: %s",
                    task.getTaskId(), task.getSubject());

        } catch (Exception e) {
            log.error("[CreateTaskTool] Failed to create task", e);
            return "Error creating task: " + e.getMessage();
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
