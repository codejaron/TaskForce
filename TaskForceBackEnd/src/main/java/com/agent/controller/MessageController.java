package com.agent.controller;

import com.agent.dto.ApiResponse;
import com.agent.entity.Message;
import com.agent.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 消息控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {
    
    private final MessageService messageService;
    
    /**
     * 获取会话的所有消息
     */
    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<Message>> getSessionMessages(@PathVariable String sessionId) {
        try {
            List<Message> messages = messageService.getSessionMessages(sessionId);
            return ApiResponse.success(messages);
        } catch (Exception e) {
            log.error("Get session messages failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 获取会话的最近N条消息
     */
    @GetMapping("/session/{sessionId}/recent")
    public ApiResponse<List<Message>> getRecentMessages(
        @PathVariable String sessionId,
        @RequestParam(defaultValue = "10") int limit
    ) {
        try {
            List<Message> messages = messageService.getRecentMessages(sessionId, limit);
            return ApiResponse.success(messages);
        } catch (Exception e) {
            log.error("Get recent messages failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 获取Agent在会话中的消息
     */
    @GetMapping("/session/{sessionId}/agent/{agentId}")
    public ApiResponse<List<Message>> getAgentMessages(
        @PathVariable String sessionId,
        @PathVariable Long agentId
    ) {
        try {
            List<Message> messages = messageService.getAgentMessages(sessionId, agentId);
            return ApiResponse.success(messages);
        } catch (Exception e) {
            log.error("Get agent messages failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 统计会话消息数
     */
    @GetMapping("/session/{sessionId}/count")
    public ApiResponse<Long> countSessionMessages(@PathVariable String sessionId) {
        try {
            long count = messageService.countSessionMessages(sessionId);
            return ApiResponse.success(count);
        } catch (Exception e) {
            log.error("Count session messages failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
