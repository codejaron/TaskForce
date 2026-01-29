package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 步骤阻塞事件
 */
@Getter
public class StepBlockedEvent extends OrchestrationEvent {

    private String stepId;
    private int stepIndex;
    private String blockedReason;

    // 无参构造函数（Jackson 反序列化需要）
    public StepBlockedEvent() {
        super();
    }

    public StepBlockedEvent(String sessionId, String stepId, int stepIndex, String blockedReason) {
        super(sessionId);
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.blockedReason = blockedReason;
    }

    @Override
    public String getEventType() {
        return "step_blocked";
    }
}
