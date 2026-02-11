package com.agent.infrastructure.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 上下文丰富拦截器
 * 将 sessionId 和 instanceId 传递到 ToolContext
 */
@Slf4j
public class ContextEnrichingToolInterceptor extends ToolInterceptor {

    private final String sessionId;
    private final String instanceId;

    public ContextEnrichingToolInterceptor(String sessionId, String instanceId) {
        this.sessionId = sessionId;
        this.instanceId = instanceId;
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        // 创建包含 sessionId 和 instanceId 的丰富上下文
        Map<String, Object> enrichedContext = new HashMap<>();
        if (request.getContext() != null) {
            enrichedContext.putAll(request.getContext());
        }

        if (sessionId != null) {
            enrichedContext.put("sessionId", sessionId);
        }

        if (instanceId != null) {
            enrichedContext.put("instanceId", instanceId);
        }

        ToolCallRequest enrichedRequest = ToolCallRequest.builder(request)
                .context(enrichedContext)
                .build();

        return handler.call(enrichedRequest);
    }

    @Override
    public String getName() {
        return "ContextEnrichingToolInterceptor";
    }
}
