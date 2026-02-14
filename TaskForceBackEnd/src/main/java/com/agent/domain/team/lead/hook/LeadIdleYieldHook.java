package com.agent.domain.team.lead.hook;

import com.agent.domain.team.lead.scheduling.LeadSchedulingDecision;
import com.agent.domain.team.lead.scheduling.LeadSchedulingDecisionService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Lead 空转让出 Hook。
 */
@Slf4j
@RequiredArgsConstructor
public class LeadIdleYieldHook extends ModelHook {

    private final String sessionId;
    private final LeadSchedulingDecisionService decisionService;

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        LeadSchedulingDecision decision = decisionService.evaluate(sessionId);
        if (decision.shouldWait()) {
            log.debug("[LeadIdleYieldHook] Yield lead by jump_to=end: sessionId={}", sessionId);
            return CompletableFuture.completedFuture(Map.of("jump_to", JumpTo.end));
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public int getOrder() {
        return 130;
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.end);
    }

    @Override
    public String getName() {
        return "LeadIdleYieldHook";
    }
}
