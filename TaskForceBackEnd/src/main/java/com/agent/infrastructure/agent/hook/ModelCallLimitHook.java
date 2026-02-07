package com.agent.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型调用次数限制 Hook
 * 防止 ReAct 循环无限执行
 */
@Slf4j
public class ModelCallLimitHook extends ModelHook {

    private final int maxCalls;
    private final AtomicInteger callCount = new AtomicInteger(0);

    public ModelCallLimitHook(int maxCalls) {
        this.maxCalls = maxCalls;
    }

    @Override
    public String getName() {
        return "ModelCallLimitHook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        int currentCount = callCount.incrementAndGet();

        if (currentCount > maxCalls) {
            log.warn("[ModelCallLimitHook] Model call limit reached: {}/{}", currentCount, maxCalls);
            throw new RuntimeException("Model call limit exceeded: " + maxCalls);
        }

        log.debug("[ModelCallLimitHook] Model call {}/{}", currentCount, maxCalls);
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public int getOrder() {
        return 100; // 较高优先级，在其他 Hook 之前执行
    }

    public void reset() {
        callCount.set(0);
    }

    public int getCurrentCount() {
        return callCount.get();
    }
}
