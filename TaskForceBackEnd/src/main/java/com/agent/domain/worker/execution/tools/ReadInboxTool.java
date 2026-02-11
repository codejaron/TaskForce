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

import java.util.List;

/**
 * 读取收件箱工具
 * Worker 使用此工具读取收到的消息
 */
@Slf4j
@Component("workerReadInboxTool")
@RequiredArgsConstructor
public class ReadInboxTool implements ToolCallback {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {}
            }
            """;

        return ToolDefinition.builder()
                .name("read_inbox")
                .description("读取当前 Worker 的收件箱消息")
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
            String instanceId = extractInstanceId(toolContext);
            List<TeamMessage> messages = inboxService.readInbox(instanceId);

            log.info("[ReadInboxTool] Read {} messages for instance: {}", messages.size(), instanceId);

            if (messages.isEmpty()) {
                return "No messages in inbox";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Found %d messages:\n\n", messages.size()));

            for (TeamMessage message : messages) {
                result.append(String.format("From: %s\n", message.getFrom()));
                result.append(String.format("Type: %s\n", message.getType()));
                result.append(String.format("Content: %s\n", message.getText()));
                result.append(String.format("Time: %s\n", message.getTimestamp()));
                result.append("\n");
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[ReadInboxTool] Failed to read inbox", e);
            return "Error reading inbox: " + e.getMessage();
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
