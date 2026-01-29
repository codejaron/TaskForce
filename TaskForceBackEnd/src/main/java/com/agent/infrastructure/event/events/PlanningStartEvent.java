package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Planning 阶段开始事件
 */
@Getter
public class PlanningStartEvent extends OrchestrationEvent {

    // 无参构造函数（Jackson 反序列化需要）
    public PlanningStartEvent() {
        super();
    }

    public PlanningStartEvent(String sessionId) {
        super(sessionId);
    }

    @Override
    public String getEventType() {
        return "planning_start";
    }
}
