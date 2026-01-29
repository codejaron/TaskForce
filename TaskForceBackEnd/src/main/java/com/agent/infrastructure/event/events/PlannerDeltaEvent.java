package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Planner 增量输出事件
 */
@Getter
public class PlannerDeltaEvent extends OrchestrationEvent {

    private String delta;

    // 无参构造函数（Jackson 反序列化需要）
    public PlannerDeltaEvent() {
        super();
    }

    public PlannerDeltaEvent(String sessionId, String delta) {
        super(sessionId);
        this.delta = delta;
    }

    @Override
    public String getEventType() {
        return "planner_delta";
    }
}
