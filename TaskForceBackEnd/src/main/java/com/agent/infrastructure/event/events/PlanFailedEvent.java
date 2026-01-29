package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 计划生成失败事件
 */
@Getter
public class PlanFailedEvent extends OrchestrationEvent {

    private String reason;

    // 无参构造函数（Jackson 反序列化需要）
    public PlanFailedEvent() {
        super();
    }

    public PlanFailedEvent(String sessionId, String reason) {
        super(sessionId);
        this.reason = reason;
    }

    @Override
    public String getEventType() {
        return "plan_failed";
    }
}
