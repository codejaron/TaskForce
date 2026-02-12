package com.agent.domain.team.lead.tools;

import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.service.WorkerInstanceManager;
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
    private final WorkerInstanceManager workerInstanceManager;

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
            String sessionId = extractSessionId(toolContext);
            String leadInstanceId = sessionId + "_lead";

            List<TeamMessage> messages = inboxService.readInbox(leadInstanceId);

            log.info("[ReadInboxTool] Read {} messages from lead inbox: sessionId={}",
                    messages.size(), sessionId);

            if (messages.isEmpty()) {
                return "No messages in inbox";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Found %d messages:\n", messages.size()));
            for (int i = 0; i < messages.size(); i++) {
                TeamMessage msg = messages.get(i);
                result.append(String.format("\n[%d] From: %s, Type: %s\nContent: %s\n",
                        i + 1, formatSender(sessionId, msg.getFrom()), msg.getType(), msg.getText()));
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[ReadInboxTool] Failed to read inbox", e);
            return "Error reading inbox: " + e.getMessage();
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

    private String formatSender(String sessionId, String sender) {
        if (sender == null || sender.isBlank()) {
            return "unknown";
        }
        if ("user".equalsIgnoreCase(sender)) {
            return "user";
        }
        if ("team-lead".equalsIgnoreCase(sender)) {
            return "lead";
        }
        if (sender.startsWith("worker-")) {
            return sender;
        }

        WorkerInstance worker = workerInstanceManager.findBySessionAndInstanceId(sessionId, sender).orElse(null);
        if (worker != null && worker.getWorkerId() > 0) {
            return "worker-" + worker.getWorkerId();
        }
        return sender;
    }
}
