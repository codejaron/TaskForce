package com.agent.infrastructure.agent.interceptor;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.ToolCallCompleteEvent;
import com.agent.infrastructure.event.events.ToolCallStartEvent;
import com.agent.service.ToolCallService;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具拦截器，发布事件并持久化工具调用记录。
 */
@Slf4j
public class EventPublishingToolInterceptor extends ToolInterceptor {

    private final String sessionId;
    private final String stepId;
    private final Integer stepIndex;
    private final Long agentId;
    private final EventBus eventBus;
    private final ToolCallService toolCallService;
    private final AtomicInteger sequenceCounter;
    private final String instanceId;

    public EventPublishingToolInterceptor(
            String sessionId,
            String stepId,
            Integer stepIndex,
            Long agentId,
            EventBus eventBus,
            ToolCallService toolCallService,
            AtomicInteger sequenceCounter,
            String instanceId) {
        this.sessionId = sessionId;
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.agentId = agentId;
        this.eventBus = eventBus;
        this.toolCallService = toolCallService;
        this.sequenceCounter = sequenceCounter;
        this.instanceId = instanceId;
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        String toolInput = request.getArguments();
        String toolCallId = UUID.randomUUID().toString();
        int sequence = sequenceCounter.getAndIncrement();

        // 从工具名称中提取服务器名称（格式：{providerName}::{toolName}）
        String serverName = extractProviderName(toolName);

        log.debug("Tool call start: {} (id: {}, step: {})", toolName, toolCallId, stepId);

        // 1. 发布开始事件
        if (eventBus != null && sessionId != null) {
            ToolCallStartEvent startEvent = new ToolCallStartEvent(
                    sessionId, stepId, stepIndex, toolCallId, toolName, serverName, toolInput, sequence
            );
            if (instanceId != null) {
                // Worker 工具调用只发 Worker 通道
                eventBus.publishToWorker(sessionId, instanceId, startEvent);
            } else {
                // Lead 或非 Worker 的工具调用发主通道
                eventBus.publish(sessionId, startEvent);
            }
        }

        // 2. Persist start record
        if (toolCallService != null && sessionId != null) {
            try {
                toolCallService.createToolCall(
                        sessionId, stepId, agentId, toolCallId, toolName, serverName, toolInput, sequence
                );
            } catch (Exception e) {
                log.warn("Failed to persist tool call start: {}", e.getMessage());
            }
        }

        // 3. 创建包含sessionId和stepIndex的上下文中的丰富ToolCallRequest
        Map<String, Object> enrichedContext = new HashMap<>();
        if (request.getContext() != null) {
            enrichedContext.putAll(request.getContext());
        }

        if (sessionId != null) {
            enrichedContext.put("sessionId", sessionId);
        }

        if (stepIndex != null) {
            enrichedContext.put("stepIndex", stepIndex);
        }

        ToolCallRequest enrichedRequest = ToolCallRequest.builder(request)
                .context(enrichedContext)
                .build();

        long startTime = System.currentTimeMillis();
        String result = null;
        String errorMessage = null;
        boolean success = true;

        try {
            // 4. Execute the tool with enriched request
            ToolCallResponse response = handler.call(enrichedRequest);
            result = response.getResult();
            return response;

        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage();
            result = "Error: " + errorMessage;
            log.error("Tool call failed: {} - {}", toolName, errorMessage);
            throw e;

        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            String status = success ? "SUCCESS" : "FAILED";

            log.debug("Tool call complete: {} (id: {}, status: {}, duration: {}ms)",
                    toolName, toolCallId, status, durationMs);

            // 5. 发布Complete事件
            if (eventBus != null && sessionId != null) {
                ToolCallCompleteEvent completeEvent = new ToolCallCompleteEvent(
                        sessionId, stepId, stepIndex, toolCallId, toolName, result, status, errorMessage, durationMs
                );
                eventBus.publish(sessionId, completeEvent);

                // 同时发到 Worker 专属通道
                if (instanceId != null) {
                    eventBus.publishToWorker(sessionId, instanceId, completeEvent);
                }
            }

            // 6. Update persist record
            if (toolCallService != null && sessionId != null) {
                try {
                    toolCallService.completeToolCall(toolCallId, result, success, errorMessage, durationMs);
                } catch (Exception e) {
                    log.warn("Failed to persist tool call complete: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public String getName() {
        return "EventPublishingToolInterceptor";
    }


    private String extractProviderName(String toolId) {
        if (toolId != null && toolId.contains("::")) {
            return toolId.substring(0, toolId.indexOf("::"));
        }
        return "unknown";
    }
}
