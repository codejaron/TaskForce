package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 计划更新事件
 */
@Getter
public class PlanUpdatedEvent extends OrchestrationEvent {

    private String planId;
    private int newTotalSteps;
    private int replanCount;

    // 无参构造函数（Jackson 反序列化需要）
    public PlanUpdatedEvent() {
        super();
    }

    public PlanUpdatedEvent(String sessionId, String planId, int newTotalSteps, int replanCount) {
        super(sessionId);
        this.planId = planId;
        this.newTotalSteps = newTotalSteps;
        this.replanCount = replanCount;
    }

    @Override
    public String getEventType() {
        return "plan_updated";
    }
}
