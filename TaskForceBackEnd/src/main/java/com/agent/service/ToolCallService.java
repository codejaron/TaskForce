package com.agent.service;

import com.agent.infrastructure.persistence.entity.ToolCall;
import com.agent.infrastructure.persistence.mapper.ToolCallMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具调用记录服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallService {

    private final ToolCallMapper toolCallMapper;

    /**
     * 创建工具调用记录（开始时）
     */
    @Transactional
    public ToolCall createToolCall(String sessionId, String stepId, Long agentId,
                                   String toolCallId, String toolName, String serverName,
                                   String toolArgs, int sequence) {
        ToolCall toolCall = ToolCall.builder()
                .sessionId(sessionId)
                .stepId(stepId)
                .agentId(agentId)
                .toolCallId(toolCallId)
                .toolName(toolName)
                .serverName(serverName)
                .toolArgs(toolArgs)
                .status("RUNNING")
                .startedAt(LocalDateTime.now())
                .sequence(sequence)
                .build();
        toolCallMapper.insert(toolCall);
        log.debug("Created tool call record: {} for tool: {}", toolCallId, toolName);
        return toolCall;
    }

    /**
     * 更新工具调用结果
     */
    @Transactional
    public void completeToolCall(String toolCallId, String result, boolean success, String errorMessage, long durationMs) {
        LocalDateTime now = LocalDateTime.now();
        String status = success ? "SUCCESS" : "FAILED";
        int updated = toolCallMapper.updateByToolCallId(toolCallId, result, status, errorMessage, now, durationMs);
        if (updated > 0) {
            log.debug("Completed tool call: {} with status: {}, duration: {}ms", toolCallId, status, durationMs);
        } else {
            log.warn("Failed to update tool call: {} - record not found", toolCallId);
        }
    }

    /**
     * 查询会话的所有工具调用
     */
    public List<ToolCall> getBySessionId(String sessionId) {
        return toolCallMapper.selectBySessionId(sessionId);
    }

    /**
     * 查询步骤的工具调用
     */
    public List<ToolCall> getByStepId(String stepId) {
        return toolCallMapper.selectByStepId(stepId);
    }

    /**
     * 根据 toolCallId 查询
     */
    public ToolCall getByToolCallId(String toolCallId) {
        return toolCallMapper.selectByToolCallId(toolCallId);
    }
}
