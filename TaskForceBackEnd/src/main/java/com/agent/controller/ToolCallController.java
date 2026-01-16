package com.agent.controller;

import com.agent.dto.ApiResponse;
import com.agent.entity.ToolCall;
import com.agent.service.ToolCallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工具调用记录控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/tool-calls")
@RequiredArgsConstructor
public class ToolCallController {

    private final ToolCallService toolCallService;

    /**
     * 获取会话的所有工具调用记录
     */
    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<ToolCall>> getBySession(@PathVariable String sessionId) {
        try {
            List<ToolCall> toolCalls = toolCallService.getBySessionId(sessionId);
            return ApiResponse.success(toolCalls);
        } catch (Exception e) {
            log.error("Get tool calls by session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取步骤的工具调用记录
     */
    @GetMapping("/step/{stepId}")
    public ApiResponse<List<ToolCall>> getByStep(@PathVariable String stepId) {
        try {
            List<ToolCall> toolCalls = toolCallService.getByStepId(stepId);
            return ApiResponse.success(toolCalls);
        } catch (Exception e) {
            log.error("Get tool calls by step failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
