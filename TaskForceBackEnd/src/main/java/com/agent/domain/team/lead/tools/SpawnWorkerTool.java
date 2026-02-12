package com.agent.domain.team.lead.tools;

import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 生成 Worker 工具
 * Lead 使用此工具创建新的 Worker 实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpawnWorkerTool implements ToolCallback {

    private final WorkerInstanceManager workerInstanceManager;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Worker 名称，描述其角色，如 'news-searcher'、'academic-searcher'"
                },
                "agentId": {
                  "type": "string",
                  "description": "Agent ID"
                },
                "assignedTaskId": {
                  "type": "integer",
                  "description": "指派给该 Worker 的任务 ID"
                }
              },
              "required": ["name", "agentId", "assignedTaskId"]
            }
            """;

        return ToolDefinition.builder()
                .name("spawn_worker")
                .description("创建并启动新的 Worker 实例，指派一个任务给它执行")
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
            String name = (String) args.get("name");
            String agentId = (String) args.get("agentId");
            int assignedTaskId = ((Number) args.get("assignedTaskId")).intValue();

            if (assignedTaskId <= 0) {
                return "Error spawning worker: assignedTaskId must be a positive task ID";
            }

            if (!isAgentAvailableInSession(sessionId, agentId)) {
                return String.format("Error spawning worker: agentId=%s is not available in session %s", agentId, sessionId);
            }

            WorkerInstance worker = workerInstanceManager.spawn(sessionId, name, agentId, "", assignedTaskId);

            log.info("[SpawnWorkerTool] Spawned worker: workerId={}, instanceId={}, name={}, assignedTaskId={}",
                    worker.getWorkerId(), worker.getInstanceId(), name, assignedTaskId);

            return String.format(
                    "Worker spawned successfully: workerId=%d, assignedTaskId=%d, name=%s, status=%s",
                    worker.getWorkerId(),
                    assignedTaskId,
                    worker.getName(),
                    worker.getStatus()
            );

        } catch (Exception e) {
            log.error("[SpawnWorkerTool] Failed to spawn worker", e);
            return "Error spawning worker: " + e.getMessage();
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

    private boolean isAgentAvailableInSession(String sessionId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }

        final long parsedAgentId;
        try {
            parsedAgentId = Long.parseLong(agentId);
        } catch (NumberFormatException e) {
            return false;
        }

        return sessionService.getSessionAgents(sessionId).stream()
                .anyMatch(sa -> sa.getAgentId() != null && sa.getAgentId().longValue() == parsedAgentId);
    }
}
