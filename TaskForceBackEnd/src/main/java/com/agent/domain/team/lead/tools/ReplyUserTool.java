package com.agent.domain.team.lead.tools;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.LeadMessageEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 回复用户工具
 * Lead 使用此工具通过 SSE 向用户发送消息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplyUserTool implements ToolCallback {

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "message": {
                  "type": "string",
                  "description": "要发送给用户的消息内容"
                }
              },
              "required": ["message"]
            }
            """;

        return ToolDefinition.builder()
                .name("reply_user")
                .description("向用户发送消息（通过 SSE 推送到前端）")
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
            String message = (String) args.get("message");

            // 创建用户回复事件
            LeadMessageEvent event = new LeadMessageEvent(sessionId, message);
            eventBus.publish(sessionId, event);

            log.info("[ReplyUserTool] Sent reply to user: sessionId={}", sessionId);

            return "Message sent to user successfully";

        } catch (Exception e) {
            log.error("[ReplyUserTool] Failed to send reply to user", e);
            return "Error sending message to user: " + e.getMessage();
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
