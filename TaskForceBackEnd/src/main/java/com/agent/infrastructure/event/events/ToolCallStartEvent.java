package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 工具调用开始事件
 */
@Getter
public class ToolCallStartEvent extends OrchestrationEvent {

    private String stepId;
    private String toolCallId;
    private String toolName;
    private String serverName;
    private String toolArgs;
    private Integer sequence;

    // 无参构造函数（Jackson 反序列化需要）
    public ToolCallStartEvent() {
        super();
    }

    public ToolCallStartEvent(String sessionId, String stepId, String toolCallId,
                              String toolName, String serverName, String toolArgs, Integer sequence) {
        super(sessionId);
        this.stepId = stepId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.serverName = serverName;
        this.toolArgs = toolArgs;
        this.sequence = sequence;
    }

    @Override
    public String getEventType() {
        return "tool_call_start";
    }
}
