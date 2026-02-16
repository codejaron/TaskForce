package com.agent.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 流式 NodeOutput 中的 token usage 跟踪器。
 * 设计目标：每次模型调用最多落库一次，避免同一轮流式 chunk 重复统计。
 */
public class TokenUsageStreamTracker {

    private final TokenUsageService tokenUsageService;
    private final String sessionId;
    private final Long agentId;
    private Usage pendingUsage;

    public TokenUsageStreamTracker(TokenUsageService tokenUsageService, String sessionId, Long agentId) {
        this.tokenUsageService = tokenUsageService;
        this.sessionId = sessionId;
        this.agentId = agentId;
    }

    public void accept(NodeOutput nodeOutput) {
        if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }

        OutputType outputType = streamingOutput.getOutputType();
        if (outputType == null) {
            return;
        }

        Usage usage = nodeOutput.tokenUsage();

        // 流式阶段先缓存 usage，最终以 FINISHED 事件为准落库。
        if (outputType == OutputType.AGENT_MODEL_STREAMING && usage != null) {
            pendingUsage = usage;
            return;
        }

        if (outputType != OutputType.AGENT_MODEL_FINISHED) {
            return;
        }

        Usage effectiveUsage = usage != null ? usage : pendingUsage;
        pendingUsage = null;
        tokenUsageService.recordUsageFromSpringUsage(sessionId, agentId, effectiveUsage);
    }
}
