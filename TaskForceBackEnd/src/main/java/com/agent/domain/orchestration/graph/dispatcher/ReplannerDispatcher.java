package com.agent.domain.orchestration.graph.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

public class ReplannerDispatcher implements EdgeAction {
    @Override
    public String apply(OverAllState state) {
        return state.value("nextAction", "complete");
    }
}
