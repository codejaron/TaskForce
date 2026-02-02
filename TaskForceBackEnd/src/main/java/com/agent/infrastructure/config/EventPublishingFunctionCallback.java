package com.agent.infrastructure.config;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.ToolCallCompleteEvent;
import com.agent.infrastructure.event.events.ToolCallStartEvent;
import com.agent.application.service.ToolCallService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件发布 FunctionCallback 包装器
 * 用于包装原生 @Tool 工具（实现 FunctionCallback 接口）
 * 在工具调用前后发布 SSE 事件并持久化记录
 */
@Slf4j
public class EventPublishingFunctionCallback implements FunctionCallback {

    private final FunctionCallback delegate;
    private final String sessionId;
    private final String stepId;
    private final Long agentId;
    private final EventBus eventBus;
    private final ToolCallService toolCallService;
    private final AtomicInteger sequenceCounter;

    public EventPublishingFunctionCallback(
            FunctionCallback delegate,
            String sessionId,
            String stepId,
            Long agentId,
            EventBus eventBus,
            ToolCallService toolCallService,
            AtomicInteger sequenceCounter) {
        this.delegate = delegate;
        this.sessionId = sessionId;
        this.stepId = stepId;
        this.agentId = agentId;
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
    public String getInputTypeSchema() {
        return delegate.getInputTypeSchema();
    }

    @Override
    public String call(String functionArguments) {
        String toolCallId = UUID.randomUUID().toString();
        String toolName = getName();
        int sequence = sequenceCounter.getAndIncrement();

        log.debug("Native tool call start: {} (id: {}, step: {})", toolName, toolCallId, stepId);

        // 1. 发布开始事件
        if (eventBus != null && sessionId != null) {
            eventBus.publish(sessionId, new ToolCallStartEvent(
                    sessionId, stepId, toolCallId, toolName, null, functionArguments, sequence
            ));
        }

        // 2. 持久化开始记录
        if (toolCallService != null && sessionId != null) {
            try {
                toolCallService.createToolCall(
                        sessionId, stepId, agentId, toolCallId, toolName, null, functionArguments, sequence
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
            result = delegate.call(functionArguments);
        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage();
            result = "Error: " + errorMessage;
            log.error("Native tool call failed: {} - {}", toolName, errorMessage);
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String status = success ? "SUCCESS" : "FAILED";

        log.debug("Native tool call complete: {} (id: {}, status: {}, duration: {}ms)", toolName, toolCallId, status, durationMs);

        // 4. 发布完成事件
        if (eventBus != null && sessionId != null) {
            eventBus.publish(sessionId, new ToolCallCompleteEvent(
                    sessionId, stepId, toolCallId, toolName, result, status, errorMessage, durationMs
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
