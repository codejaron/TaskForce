package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

import java.util.List;

/**
 * 层级开始事件
 * 用于通知前端某一层级的步骤开始并行执行
 */
@Getter
public class LayerStartEvent extends OrchestrationEvent {

    private int layerIndex;
    private List<String> stepIds;
    private int stepCount;

    public LayerStartEvent() {
        super();
    }

    public LayerStartEvent(String sessionId, int layerIndex, List<String> stepIds, int stepCount) {
        super(sessionId);
        this.layerIndex = layerIndex;
        this.stepIds = stepIds;
        this.stepCount = stepCount;
    }

    @Override
    public String getEventType() {
        return "layer_start";
    }
}
