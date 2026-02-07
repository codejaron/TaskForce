package com.agent.domain.orchestration.graph.node;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.LayerCompleteEvent;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 层级聚合节点
 * 作为并行层的同步点，确保所有并行步骤都完成后再继续
 * 实际的结果分析在 DynamicExecutorNode 中进行
 */
@Slf4j
@RequiredArgsConstructor
public class LayerAggregatorNode implements NodeAction {

    private final int layerIndex;
    private final EventBus eventBus;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");

        log.info("[LayerAggregatorNode] Aggregating layer {}: sessionId={}", layerIndex, sessionId);

        // LayerAggregatorNode 作为同步点，确保所有并行步骤都完成
        // 实际的结果分析在 DynamicExecutorNode 中进行（从数据库读取最新的步骤状态）

        // 发布层级完成事件（具体的成功/失败数量在 DynamicExecutorNode 中统计）
        eventBus.publish(sessionId, new LayerCompleteEvent(
                sessionId, layerIndex, 0, 0));

        log.info("[LayerAggregatorNode] Layer {} aggregation completed", layerIndex);

        // 返回层级索引，供后续节点使用
        return Map.of("layerIndex", layerIndex);
    }
}
