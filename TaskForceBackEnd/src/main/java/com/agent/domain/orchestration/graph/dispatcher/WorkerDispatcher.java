package com.agent.domain.orchestration.graph.dispatcher;


import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

/**
 * Worker 节点的条件边调度器
 * 根据 nextAction 状态决定下一步流程
 */
public class WorkerDispatcher implements EdgeAction {
    
    @Override
    public String apply(OverAllState state) {
        String nextAction = state.value("nextAction", "complete");
        return nextAction;
    }
}
