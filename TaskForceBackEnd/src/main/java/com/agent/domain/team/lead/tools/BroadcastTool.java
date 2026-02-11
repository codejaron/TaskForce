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
 * 广播消息工具
 * Lead 使用此工具向所有 Worker 广播消息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastTool implements ToolCallback {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "content": {
                  "type": "string",
                  "description": "广播消息内容"
                },
                "messageType": {
                  "type": "string",
                  "description": "消息类型（可选，默认为 ANNOUNCEMENT）"
                }
              },
              "required": ["content"]
            }
            """;

        return ToolDefinition.builder()
                .name("broadcast")
                .description("向所有 Worker 广播消息")
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
            String content = (String) args.get("content");
            String messageType = (String) args.getOrDefault("messageType", "ANNOUNCEMENT");

            TeamMessage message = TeamMessage.builder()
                    .from("team-lead")
                    .to("all")
                    .text(content)
                    .type(messageType)
                    .build();

            inboxService.broadcast(sessionId, message);

            log.info("[BroadcastTool] Broadcasted message to all workers in session: {}", sessionId);

            return "Message broadcasted successfully to all workers";

        } catch (Exception e) {
            log.error("[BroadcastTool] Failed to broadcast message", e);
            return "Error broadcasting message: " + e.getMessage();
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
