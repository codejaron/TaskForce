package com.agent.domain.orchestration.graph.dispatcher;


import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

/**
 * Planner 节点的条件边调度器
 * 根据 nextAction 状态决定下一步流程
 */
public class PlannerDispatcher implements EdgeAction {
    
    @Override
    public String apply(OverAllState state) {
        String nextAction = state.value("nextAction", "cannot_plan");
        return nextAction;
    }
}
