package com.agent.infrastructure.config;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.ToolCallCompleteEvent;
import com.agent.infrastructure.event.events.ToolCallStartEvent;
import com.agent.service.ToolCallService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 事件发布工具回调包装器
 * 在工具调用前后发布 SSE 事件并持久化记录
 */
@Slf4j
public class EventPublishingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String sessionId;
    private final String stepId;
    private final Integer stepIndex;  // 新增
    private final Long agentId;
    private final String serverName;  // MCP Server 名称，原生工具为 null
    private final EventBus eventBus;
    private final ToolCallService toolCallService;
    private final AtomicInteger sequenceCounter;

    public EventPublishingToolCallback(
            ToolCallback delegate,
            String sessionId,
            String stepId,
            Integer stepIndex,
            Long agentId,
            String serverName,
            EventBus eventBus,
            ToolCallService toolCallService,
            AtomicInteger sequenceCounter) {
        this.delegate = delegate;
        this.sessionId = sessionId;
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.agentId = agentId;
        this.serverName = serverName;
        this.eventBus = eventBus;
        this.toolCallService = toolCallService;
        this.sequenceCounter = sequenceCounter;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return executeWithEvents(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // 把 sessionId 塞进 toolContext
        ToolContext enrichedContext = enrichWithSessionId(toolContext);
        return executeWithEvents(toolInput, () -> delegate.call(toolInput, enrichedContext));
    }

    private ToolContext enrichWithSessionId(ToolContext original) {
        Map<String, Object> map = new HashMap<>();
        if (original != null && original.getContext() != null) {
            map.putAll(original.getContext());
        }
        map.put("sessionId", this.sessionId);  // 关键：把 sessionId 塞进去
        return new ToolContext(map);
    }

    private String executeWithEvents(String toolInput, Supplier<String> execution) {
        String toolCallId = UUID.randomUUID().toString();
        String toolName = getToolDefinition().name();
        int sequence = sequenceCounter.getAndIncrement();

        log.debug("Tool call start: {} (id: {}, step: {})", toolName, toolCallId, stepId);

        // 1. 发布开始事件
        if (eventBus != null && sessionId != null) {
            eventBus.publish(sessionId, new ToolCallStartEvent(
                    sessionId, stepId, stepIndex, toolCallId, toolName, serverName, toolInput, sequence
            ));
        }

        // 2. 持久化开始记录（不保存 stepIndex）
        if (toolCallService != null && sessionId != null) {
            try {
                toolCallService.createToolCall(
                        sessionId, stepId, agentId, toolCallId, toolName, serverName, toolInput, sequence
                );
            } catch (Exception e) {
                log.warn("Failed to persist tool call start: {}", e.getMessage());
            }
        }

        long startTime = System.currentTimeMillis();
        String result = null;
        String errorMessage = null;
        boolean success = true;

        try {
            // 3. 执行工具
            result = execution.get();
        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage();
            result = "Error: " + errorMessage;
            log.error("Tool call failed: {} - {}", toolName, errorMessage);
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String status = success ? "SUCCESS" : "FAILED";

        log.debug("Tool call complete: {} (id: {}, status: {}, duration: {}ms)", toolName, toolCallId, status, durationMs);

        // 4. 发布完成事件
        if (eventBus != null && sessionId != null) {
            eventBus.publish(sessionId, new ToolCallCompleteEvent(
                    sessionId, stepId, stepIndex, toolCallId, toolName, result, status, errorMessage, durationMs
            ));
        }

        // 5. 更新持久化记录
        if (toolCallService != null && sessionId != null) {
            try {
                toolCallService.completeToolCall(toolCallId, result, success, errorMessage, durationMs);
            } catch (Exception e) {
                log.warn("Failed to persist tool call complete: {}", e.getMessage());
            }
        }

        return result;
    }
}
