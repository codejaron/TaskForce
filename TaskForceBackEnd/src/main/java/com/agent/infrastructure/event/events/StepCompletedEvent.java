package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 步骤完成事件
 */
@Getter
public class StepCompletedEvent extends OrchestrationEvent {

    private String stepId;
    private int stepIndex;
    private String result;

    // 无参构造函数（Jackson 反序列化需要）
    public StepCompletedEvent() {
        super();
    }

    public StepCompletedEvent(String sessionId, String stepId, int stepIndex, String result) {
        super(sessionId);
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.result = result;
    }

    @Override
    public String getEventType() {
        return "step_completed";
    }
}
