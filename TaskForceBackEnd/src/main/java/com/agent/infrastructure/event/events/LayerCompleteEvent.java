package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 层级完成事件
 * 用于通知前端某一层级的所有步骤已完成
 */
@Getter
public class LayerCompleteEvent extends OrchestrationEvent {

    private int layerIndex;
    private int successCount;
    private int failedCount;

    public LayerCompleteEvent() {
        super();
    }

    public LayerCompleteEvent(String sessionId, int layerIndex, int successCount, int failedCount) {
        super(sessionId);
        this.layerIndex = layerIndex;
        this.successCount = successCount;
        this.failedCount = failedCount;
    }

    @Override
    public String getEventType() {
        return "layer_complete";
    }
}
