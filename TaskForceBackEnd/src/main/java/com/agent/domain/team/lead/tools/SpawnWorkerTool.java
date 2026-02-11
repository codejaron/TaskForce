package com.agent.domain.team.lead.tools;

import com.agent.domain.worker.model.WorkerInstance;
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
 * 生成 Worker 工具
 * Lead 使用此工具创建新的 Worker 实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpawnWorkerTool implements ToolCallback {

    private final WorkerInstanceManager workerInstanceManager;
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
                "initialPrompt": {
                  "type": "string",
                  "description": "Worker 的角色指令，描述它的职责和工作方式"
                },
                "assignedTaskId": {
                  "type": "integer",
                  "description": "指派给该 Worker 的任务 ID（来自 create_task 返回的 taskId）"
                }
              },
              "required": ["name", "agentId", "initialPrompt", "assignedTaskId"]
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
            String initialPrompt = (String) args.get("initialPrompt");
            int assignedTaskId = ((Number) args.get("assignedTaskId")).intValue();

            WorkerInstance worker = workerInstanceManager.spawn(sessionId, name, agentId, initialPrompt, assignedTaskId);

            log.info("[SpawnWorkerTool] Spawned worker: instanceId={}, name={}, assignedTaskId={}",
                    worker.getInstanceId(), name, assignedTaskId);

            return String.format("Worker spawned successfully: instanceId=%s, name=%s, assignedTaskId=%d, status=%s",
                    worker.getInstanceId(), worker.getName(), assignedTaskId, worker.getStatus());

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
}
