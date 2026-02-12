package com.agent.domain.team.lead.tools;

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
 * 列出团队成员工具
 * Lead 使用此工具查看所有团队成员状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListTeammatesTool implements ToolCallback {

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
                .name("list_teammates")
                .description("列出当前团队的所有成员（包括 Lead 和其他 Worker）及其状态")
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
            List<WorkerInstance> workers = workerInstanceManager.getRunningWorkers(sessionId);
            log.info("[ListTeammatesTool] Listed {} workers for session: {}", workers.size(), sessionId);

            StringBuilder result = new StringBuilder();
            result.append(String.format("Session: %s\n", sessionId));
            result.append("Lead: team-lead\n");
            result.append(String.format("Found %d running workers:\n\n", workers.size()));

            for (WorkerInstance worker : workers) {
                result.append(String.format("Worker #%d\n", worker.getWorkerId()));
                result.append(String.format("  Name: %s\n", worker.getName()));
                result.append(String.format("  Agent ID: %s\n", worker.getAgentId()));
                result.append(String.format("  Status: %s\n", worker.getStatus()));
                result.append(String.format("  Assigned Task: %s\n",
                        worker.getAssignedTaskId() == 0 ? "None" : "#" + worker.getAssignedTaskId()));
                result.append(String.format("  Current Task: %s\n",
                        worker.getCurrentTaskId() == 0 ? "None" : "#" + worker.getCurrentTaskId()));
                result.append("\n");
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[ListTeammatesTool] Failed to list teammates", e);
            return "Error listing teammates: " + e.getMessage();
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
