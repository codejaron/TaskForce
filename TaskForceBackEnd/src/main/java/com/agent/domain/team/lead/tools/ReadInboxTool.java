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

import java.util.List;

/**
 * 读取收件箱工具
 * Lead 使用此工具读取收到的消息
 */
@Slf4j
@Component("leadReadInboxTool")
@RequiredArgsConstructor
public class ReadInboxTool implements ToolCallback {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "instanceId": {
                  "type": "string",
                  "description": "Lead 实例 ID"
                }
              },
              "required": ["instanceId"]
            }
            """;

        return ToolDefinition.builder()
                .name("read_inbox")
                .description("读取收件箱中的消息")
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
            var args = objectMapper.readValue(toolInput, java.util.Map.class);
            String instanceId = (String) args.get("instanceId");

            List<TeamMessage> messages = inboxService.readInbox(instanceId);

            log.info("[ReadInboxTool] Read {} messages from inbox: instanceId={}",
                    messages.size(), instanceId);

            if (messages.isEmpty()) {
                return "No messages in inbox";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Found %d messages:\n", messages.size()));
            for (int i = 0; i < messages.size(); i++) {
                TeamMessage msg = messages.get(i);
                result.append(String.format("\n[%d] From: %s, Type: %s\nContent: %s\n",
                        i + 1, msg.getFrom(), msg.getType(), msg.getText()));
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[ReadInboxTool] Failed to read inbox", e);
            return "Error reading inbox: " + e.getMessage();
        }
    }
}
