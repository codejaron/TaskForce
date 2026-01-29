package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Replanner 增量输出事件
 */
@Getter
public class ReplannerDeltaEvent extends OrchestrationEvent {

    private String delta;

    // 无参构造函数（Jackson 反序列化需要）
    public ReplannerDeltaEvent() {
        super();
    }

    public ReplannerDeltaEvent(String sessionId, String delta) {
        super(sessionId);
        this.delta = delta;
    }

    @Override
    public String getEventType() {
        return "replanner_delta";
    }
}
