package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 工具调用完成事件
 */
@Getter
public class ToolCallCompleteEvent extends OrchestrationEvent {

    private String stepId;
    private Integer stepIndex;  // 新增
    private String toolCallId;
    private String toolName;
    private String toolResult;
    private String instanceId;
    private String status;  // SUCCESS/FAILED
    private String errorMessage;
    private Long durationMs;

    // 无参构造函数（Jackson 反序列化需要）
    public ToolCallCompleteEvent() {
        super();
    }

    public ToolCallCompleteEvent(String sessionId, String stepId, Integer stepIndex,
                                 String toolCallId, String toolName, String toolResult,
                                 String status, String errorMessage, Long durationMs, String instanceId) {
        super(sessionId);
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolResult = toolResult;
        this.instanceId = instanceId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
    }

    @Override
    public String getEventType() {
        return "tool_call_complete";
    }
}
