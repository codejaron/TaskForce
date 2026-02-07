package com.agent.domain.orchestration.graph.node;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.LayerStartEvent;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 分发节点（Pass-through）
 * 用于规避 START 直接并行的 bug (#4097)
 * 发布层级开始事件
 */
@Slf4j
@RequiredArgsConstructor
public class DispatchNode implements NodeAction {

    private final int layerIndex;
    private final List<String> stepIds;
    private final EventBus eventBus;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        log.debug("[DispatchNode] Dispatching layer {}: sessionId={}, steps={}",
                layerIndex, sessionId, stepIds.size());

        // 发布层级开始事件
        eventBus.publish(sessionId, new LayerStartEvent(
                sessionId, layerIndex, stepIds, stepIds.size()));

        // Pass-through，不修改状态
        return Map.of("layerIndex", layerIndex);
    }
}
