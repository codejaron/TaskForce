package com.agent.domain.worker.execution.tools;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
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
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "target": {
                  "type": "string",
                  "description": "目标接收者的实例 ID 或名称"
                },
                "text": {
                  "type": "string",
                  "description": "消息内容"
                },
                "messageType": {
                  "type": "string",
                  "description": "消息类型（可选，默认为 MESSAGE）"
                }
              },
              "required": ["target", "text"]
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

            String instanceId = extractInstanceId(toolContext);
            String target = (String) args.get("target");
            String text = (String) args.get("text");
            String messageType = (String) args.getOrDefault("messageType", "MESSAGE");

            TeamMessage message = TeamMessage.builder()
                    .from(instanceId)
                    .to(target)
                    .text(text)
                    .type(messageType)
                    .build();

            inboxService.send(message);

            log.info("[SendMessageTool] Sent message from {} to {}", instanceId, target);

            return String.format("Message sent successfully to %s", target);

        } catch (Exception e) {
            log.error("[SendMessageTool] Failed to send message", e);
            return "Error sending message: " + e.getMessage();
        }
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
