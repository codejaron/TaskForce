package com.agent.domain.team.lead.tools;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
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
 * 发送消息工具
 * Lead 使用此工具向特定 Worker 发送消息
 */
@Slf4j
@Component("leadSendMessageTool")
@RequiredArgsConstructor
public class SendMessageTool implements ToolCallback {

    private final InboxService inboxService;
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
                  "description": "接收消息的 Worker ID（会话内数字编号，例如 1/2/3）"
                },
                "content": {
                  "type": "string",
                  "description": "消息内容"
                },
                "messageType": {
                  "type": "string",
                  "description": "消息类型（可选，默认为 INSTRUCTION）"
                }
              },
              "required": ["workerId", "content"]
            }
            """;

        return ToolDefinition.builder()
                .name("send_message")
                .description("向特定 Worker 发送消息")
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
            String content = (String) args.get("content");
            String messageType = (String) args.getOrDefault("messageType", "INSTRUCTION");

            WorkerInstance worker = workerInstanceManager.findBySessionAndWorkerId(sessionId, workerId)
                    .orElse(null);
            if (worker == null || worker.isShutdown()) {
                return String.format("Worker #%d not found in current session", workerId);
            }

            TeamMessage message = TeamMessage.builder()
                    .from("team-lead")
                    .to(worker.getInstanceId())
                    .text(content)
                    .type(messageType)
                    .build();

            inboxService.send(message);

            log.info("[SendMessageTool] Sent message: sessionId={}, workerId={}, instanceId={}",
                    sessionId, workerId, worker.getInstanceId());

            return String.format("Message sent successfully to worker #%d", workerId);

        } catch (Exception e) {
            log.error("[SendMessageTool] Failed to send message", e);
            return "Error sending message: " + e.getMessage();
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
