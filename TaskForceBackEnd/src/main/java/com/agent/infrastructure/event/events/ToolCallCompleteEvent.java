package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 工具调用完成事件
 */
@Getter
public class ToolCallCompleteEvent extends OrchestrationEvent {

    private String stepId;
    private String toolCallId;
    private String toolName;
    private String toolResult;
    private String status;  // SUCCESS/FAILED
    private String errorMessage;
    private Long durationMs;

    // 无参构造函数（Jackson 反序列化需要）
    public ToolCallCompleteEvent() {
        super();
    }

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
