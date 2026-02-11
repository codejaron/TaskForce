package com.agent.domain.team.lead.tools;

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
 * Lead 使用此工具向特定 Worker 发送消息
 */
@Slf4j
@Component("leadSendMessageTool")
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
                "recipient": {
                  "type": "string",
                  "description": "接收者实例 ID"
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
              "required": ["recipient", "content"]
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

            String recipient = (String) args.get("recipient");
            String content = (String) args.get("content");
            String messageType = (String) args.getOrDefault("messageType", "INSTRUCTION");

            TeamMessage message = TeamMessage.builder()
                    .from("team-lead")
                    .to(recipient)
                    .text(content)
                    .type(messageType)
                    .build();

            inboxService.send(message);

            log.info("[SendMessageTool] Sent message to: recipient={}", recipient);

            return String.format("Message sent successfully to: %s", recipient);

        } catch (Exception e) {
            log.error("[SendMessageTool] Failed to send message", e);
            return "Error sending message: " + e.getMessage();
        }
    }
}
