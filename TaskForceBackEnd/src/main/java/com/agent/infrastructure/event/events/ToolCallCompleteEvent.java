package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 工具调用完成事件
 */
@Getter
public class ToolCallCompleteEvent extends OrchestrationEvent {

    private final String stepId;
    private final String toolCallId;
    private final String toolName;
    private final String toolResult;
    private final String status;  // SUCCESS/FAILED
    private final String errorMessage;
    private final Long durationMs;

    public ToolCallCompleteEvent(String sessionId, String stepId, String toolCallId,
                                 String toolName, String toolResult, String status,
                                 String errorMessage, Long durationMs) {
        super(sessionId);
        this.stepId = stepId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolResult = toolResult;
        this.status = status;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
    }

    @Override
    public String getEventType() {
        return "tool_call_complete";
    }
}
