package com.agent.domain.orchestration.graph.node;

import com.agent.domain.orchestration.state.StateManager;
import com.agent.domain.orchestration.model.ExecutionPlan;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.*;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Replanner Node
 * 负责在步骤阻塞时重新规划（简化版本）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplannerNode implements NodeAction {

    private final StateManager stateManager;
    private final EventBus eventBus;
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", "");
        int stepIndex = state.value("currentStepIndex", 0);
        
        log.info("[ReplannerNode] Replanning: sessionId={}, stepIndex={}", sessionId, stepIndex);
        
        // 加载当前计划
        ExecutionPlan plan = stateManager.loadPlan(sessionId);
        
        // 发布重规划开始事件
        eventBus.publish(sessionId, new ReplanningStartEvent(sessionId, "步骤阻塞，需要重规划"));
        
        // TODO: 实现重规划逻辑
        // 这里简化处理：如果重规划次数未超限，重置当前步骤为 PENDING 并返回 worker
        // 实际应该调用 LLM 重新生成计划
        
        log.warn("[ReplannerNode] Replanning not fully implemented, resetting blocked step");
        
        // 简单处理：重置当前步骤并继续
        return Map.of(
            "nextAction", "continue",
            "currentStepIndex", stepIndex
        );
    }
}
