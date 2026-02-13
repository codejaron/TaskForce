package com.agent.domain.worker.execution.tools;

import com.agent.domain.execution.service.ExecutionWaitIntentService;
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
 * Worker 使用此工具向其他 Worker 或 Lead 发送消息
 */
@Slf4j
@Component("workerSendMessageTool")
@RequiredArgsConstructor
public class SendMessageTool implements ToolCallback {

    private final InboxService inboxService;
    private final WorkerInstanceManager workerInstanceManager;
    private final ExecutionWaitIntentService waitIntentService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "workerId": {
                  "type": "integer",
                  "description": "目标 Worker ID（1/2/3...），发送给 Lead 时填 0"
                },
                "text": {
                  "type": "string",
                  "description": "消息内容"
                },
                "messageType": {
                  "type": "string",
                  "description": "消息类型（可选，默认为 MESSAGE）"
                },
                "expectReply": {
                  "type": "boolean",
                  "description": "是否需要等待对方回复。为 true 时当前 Worker 本轮执行结束后进入 WAITING_REPLY。"
                }
              },
              "required": ["workerId", "text"]
            }
            """;

        return ToolDefinition.builder()
                .name("send_message")
                .description("向指定的 Worker 或 Lead 发送消息")
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
            int workerId = ((Number) args.get("workerId")).intValue();
            String text = (String) args.get("text");
            String messageType = (String) args.getOrDefault("messageType", "MESSAGE");
            boolean expectReply = Boolean.TRUE.equals(args.get("expectReply"));

            WorkerInstance senderWorker = workerInstanceManager.findBySessionAndInstanceId(sessionId, instanceId).orElse(null);
            String sender = senderWorker != null && senderWorker.getWorkerId() > 0
                    ? "worker-" + senderWorker.getWorkerId()
                    : "worker";

            String targetInstanceId;
            String targetLabel;
            if (workerId == 0) {
                targetInstanceId = sessionId + "_lead";
                targetLabel = "lead";
            } else {
                WorkerInstance targetWorker = workerInstanceManager.findBySessionAndWorkerId(sessionId, workerId).orElse(null);
                if (targetWorker == null || targetWorker.isShutdown()) {
                    return String.format("Worker #%d not found in current session", workerId);
                }
                targetInstanceId = targetWorker.getInstanceId();
                targetLabel = "worker-" + workerId;
            }

            TeamMessage message = TeamMessage.builder()
                    .from(sender)
                    .to(targetInstanceId)
                    .text(text)
                    .type(messageType)
                    .build();

            inboxService.send(message);

            if (expectReply) {
                waitIntentService.markWaitingReply(instanceId, "send_message expectReply=true");
            }

            log.info("[SendMessageTool] Sent message from {} to {}", sender, targetLabel);

            if (expectReply) {
                return String.format("Message sent successfully to %s, waiting for reply", targetLabel);
            }
            return String.format("Message sent successfully to %s", targetLabel);

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
