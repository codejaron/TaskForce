package com.agent.domain.team.context;

import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.infrastructure.mcp.RemoteMcpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Team 模式任务上下文服务。
 * 仅提供三类信息：
 * 1. 项目根目录 TASKFORCE.md
 * 2. 依赖任务 completionNote
 * 3. 当前任务描述
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamTaskContextService {

    private static final String TASKFORCE_MD = "TASKFORCE.md";

    private final TaskBoardService taskBoardService;
    private final RemoteMcpClient remoteMcpClient;
    private final ObjectMapper objectMapper;

    public String buildTaskInstruction(String sessionId, Task currentTask) {
        StringBuilder instruction = new StringBuilder();

        instruction.append("## 项目约束（TASKFORCE.md）\n\n");
        String taskforceContent = readTaskforceMarkdown(sessionId);
        if (taskforceContent == null || taskforceContent.isBlank()) {
            instruction.append("未读取到 TASKFORCE.md。")
                    .append("如果项目没有该文件，请先用 glob 或 read 自行确认。\n\n");
        } else {
            instruction.append(taskforceContent).append("\n\n");
        }

        instruction.append("## 依赖任务 completionNote\n\n");
        appendDependencyNotes(instruction, sessionId, currentTask.getTaskId());

        instruction.append("\n## 当前任务\n\n");
        instruction.append("Task #").append(currentTask.getTaskId()).append(": ")
                .append(safe(currentTask.getSubject())).append("\n\n");
        instruction.append(safe(currentTask.getDescription())).append("\n\n");
        instruction.append("你需要在项目目录内自行获取代码上下文。")
                .append("优先使用 read / grep / glob / bash。\n");
        instruction.append("完成后必须调用 complete_task，summary 填一句话，作为 completionNote。\n");

        return instruction.toString();
    }

    private void appendDependencyNotes(StringBuilder builder, String sessionId, int currentTaskId) {
        List<Task> dependencies = taskBoardService.listTasks(sessionId).stream()
                .filter(task -> task.getBlocks() != null && task.getBlocks().contains(currentTaskId))
                .sorted(Comparator.comparingInt(Task::getTaskId))
                .toList();

        if (dependencies.isEmpty()) {
            builder.append("- 无依赖任务。\n");
            return;
        }

        for (Task dependency : dependencies) {
            builder.append("- Task #").append(dependency.getTaskId())
                    .append(" ").append(safe(dependency.getSubject()))
                    .append(" [").append(dependency.getStatus()).append("]：");

            String note = dependency.getCompletionNote();
            if (note != null && !note.isBlank()) {
                builder.append(note.trim());
            } else if (dependency.isCompleted()) {
                builder.append("（未填写 completionNote）");
            } else {
                builder.append("（尚未完成）");
            }
            builder.append("\n");
        }
    }

    private String readTaskforceMarkdown(String sessionId) {
        try {
            RemoteMcpClient.ToolCallResultDTO result = remoteMcpClient.callTool(
                    "native::read",
                    Map.of("filePath", TASKFORCE_MD, "offset", 1, "limit", 400),
                    sessionId,
                    null
            );

            if (result == null) {
                return null;
            }
            if (Boolean.TRUE.equals(result.getIsError())) {
                String text = result.getTextContent();
                log.info("[TeamTaskContext] TASKFORCE.md unavailable for session {}: {}", sessionId, text);
                return null;
            }

            return extractReadContent(result.getTextContent());
        } catch (Exception e) {
            log.info("[TeamTaskContext] Failed to read TASKFORCE.md for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private String extractReadContent(String toolText) {
        if (toolText == null || toolText.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(toolText);
            if (!root.path("success").asBoolean(false)) {
                return null;
            }
            JsonNode content = root.get("content");
            if (content != null && content.isTextual()) {
                return content.asText();
            }
        } catch (Exception ignore) {
            // Non-JSON fallback.
        }
        return toolText;
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
