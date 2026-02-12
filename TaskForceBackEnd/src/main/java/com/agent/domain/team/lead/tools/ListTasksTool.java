package com.agent.domain.team.lead.tools;

import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.service.WorkerInstanceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 列出任务工具
 * Lead 使用此工具查看所有任务状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListTasksTool implements ToolCallback {

    private final TaskBoardService taskBoardService;
    private final WorkerInstanceManager workerInstanceManager;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {}
            }
            """;

        return ToolDefinition.builder()
                .name("list_tasks")
                .description("列出当前会话的所有任务及其状态")
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
            String sessionId = extractSessionId(toolContext);
            List<Task> tasks = taskBoardService.listTasks(sessionId);

            log.info("[ListTasksTool] Listed {} tasks for session: {}", tasks.size(), sessionId);

            if (tasks.isEmpty()) {
                return "No tasks found";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Found %d tasks:\n\n", tasks.size()));

            for (Task task : tasks) {
                result.append(String.format("Task #%d: %s\n", task.getTaskId(), task.getSubject()));
                result.append(String.format("  Status: %s\n", task.getStatus()));
                result.append(String.format("  Owner: %s\n", formatOwner(sessionId, task.getOwner())));

                if (task.getBlockedBy() != null && !task.getBlockedBy().isEmpty()) {
                    String deps = task.getBlockedBy().stream()
                            .map(id -> "#" + id)
                            .collect(java.util.stream.Collectors.joining(", "));
                    result.append(String.format("  Blocked By: %s\n", deps));
                }

                if (task.getBlocks() != null && !task.getBlocks().isEmpty()) {
                    String blocks = task.getBlocks().stream()
                            .map(id -> "#" + id)
                            .collect(java.util.stream.Collectors.joining(", "));
                    result.append(String.format("  Blocks: %s\n", blocks));
                }

                result.append("\n");
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[ListTasksTool] Failed to list tasks", e);
            return "Error listing tasks: " + e.getMessage();
        }
    }

    private String formatOwner(String sessionId, String ownerInstanceId) {
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            return "None";
        }
        WorkerInstance worker = workerInstanceManager.findBySessionAndInstanceId(sessionId, ownerInstanceId).orElse(null);
        if (worker != null && worker.getWorkerId() > 0) {
            return "Worker #" + worker.getWorkerId();
        }
        return "Unknown worker";
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
