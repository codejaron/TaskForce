package com.agent.domain.team.lead.tools;

import com.agent.domain.execution.service.ExecutionWaitIntentService;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;
import com.agent.domain.taskboard.service.TaskBoardService;
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
    private final TaskBoardService taskBoardService;
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
                  "description": "接收消息的 Worker ID（会话内数字编号，例如 1/2/3）"
                },
                "content": {
                  "type": "string",
                  "description": "消息内容"
                },
                "messageType": {
                  "type": "string",
                  "description": "消息类型（可选，默认为 USER_MESSAGE）"
                },
                "assignTask": {
                  "type": "boolean",
                  "description": "是否将该消息作为派工动作。为 true 时会先更新任务板分配状态，再通知 Worker。"
                },
                "taskId": {
                  "type": "integer",
                  "description": "任务 ID。仅当 assignTask=true 时必填。"
                },
                "expectReply": {
                  "type": "boolean",
                  "description": "是否等待该 Worker 回复。为 true 时 Lead 本轮执行结束后进入 WAITING_REPLY。"
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
            String messageType = (String) args.getOrDefault("messageType", "USER_MESSAGE");
            boolean assignTask = Boolean.TRUE.equals(args.get("assignTask"));
            Integer taskId = parseOptionalTaskId(args.get("taskId"));
            boolean expectReply = Boolean.TRUE.equals(args.get("expectReply"));

            WorkerInstance worker = workerInstanceManager.findBySessionAndWorkerId(sessionId, workerId)
                    .orElse(null);
            if (worker == null || worker.isShutdown()) {
                return String.format("Worker #%d not found in current session", workerId);
            }

            if (assignTask) {
                if (taskId == null || taskId <= 0) {
                    return "Error sending message: taskId is required and must be positive when assignTask=true";
                }
                String assignmentResult = ensureTaskAssigned(sessionId, taskId, worker);

                TeamMessage assignmentMessage = TeamMessage.builder()
                        .from("team-lead")
                        .fromInstanceId(sessionId + "_lead")
                        .to(worker.getInstanceId())
                        .text(String.valueOf(taskId))
                        .type("ASSIGN_TASK")
                        .build();
                inboxService.send(assignmentMessage);

                if (content != null && !content.isBlank()) {
                    String instructionType = messageType == null || messageType.isBlank()
                            || "ASSIGN_TASK".equalsIgnoreCase(messageType)
                            ? "INSTRUCTION"
                            : messageType;
                    TeamMessage instructionMessage = TeamMessage.builder()
                            .from("team-lead")
                            .fromInstanceId(sessionId + "_lead")
                            .to(worker.getInstanceId())
                            .text(content)
                            .type(instructionType)
                            .build();
                    inboxService.send(instructionMessage);
                }

                if (expectReply) {
                    waitIntentService.markWaitingReply(sessionId + "_lead", "lead send_message expectReply=true");
                }

                log.info("[SendMessageTool] Assigned task and sent message: sessionId={}, workerId={}, instanceId={}, taskId={}, assignmentResult={}",
                        sessionId, workerId, worker.getInstanceId(), taskId, assignmentResult);

                if (expectReply) {
                    return String.format("Task #%d assigned to worker #%d (%s), instruction sent, waiting for reply",
                            taskId,
                            workerId,
                            assignmentResult);
                }
                return String.format("Task #%d assigned to worker #%d (%s), instruction sent",
                        taskId,
                        workerId,
                        assignmentResult);
            }

            TeamMessage message = TeamMessage.builder()
                    .from("team-lead")
                    .fromInstanceId(sessionId + "_lead")
                    .to(worker.getInstanceId())
                    .text(content)
                    .type(messageType)
                    .build();
            inboxService.send(message);

            if (expectReply) {
                waitIntentService.markWaitingReply(sessionId + "_lead", "lead send_message expectReply=true");
            }
            log.info("[SendMessageTool] Sent message: sessionId={}, workerId={}, instanceId={}",
                    sessionId, workerId, worker.getInstanceId());

            if (expectReply) {
                return String.format("Message sent successfully to worker #%d, waiting for reply", workerId);
            }
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

    private Integer parseOptionalTaskId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        throw new IllegalArgumentException("taskId must be a number");
    }

    private String ensureTaskAssigned(String sessionId, int taskId, WorkerInstance worker) {
        Task task = taskBoardService.getTask(sessionId, taskId);
        String workerInstanceId = worker.getInstanceId();

        if (task.getStatus() == TaskStatus.PENDING) {
            taskBoardService.assignTask(sessionId, taskId, workerInstanceId);
            return "newly-assigned";
        }

        if (task.getStatus() == TaskStatus.ASSIGNED && workerInstanceId.equals(task.getOwner())) {
            return "already-assigned";
        }

        throw new IllegalStateException(String.format(
                "Task #%d is not dispatchable (status=%s, owner=%s)",
                taskId,
                task.getStatus(),
                task.getOwner()
        ));
    }
}
