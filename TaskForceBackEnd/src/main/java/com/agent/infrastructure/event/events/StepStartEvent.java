package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 步骤开始事件
 */
@Getter
public class StepStartEvent extends OrchestrationEvent {

    private String stepId;
    private int stepIndex;
    private String instruction;
    private String assignedAgentId;
    private String assignedAgentName;

    // 无参构造函数（Jackson 反序列化需要）
    public StepStartEvent() {
        super();
    }

    public StepStartEvent(String sessionId, String stepId, int stepIndex,
                          String instruction, String assignedAgentId, String assignedAgentName) {
        super(sessionId);
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.instruction = instruction;
        this.assignedAgentId = assignedAgentId;
        this.assignedAgentName = assignedAgentName;
    }

    @Override
    public String getEventType() {
        return "step_start";
    }
}
