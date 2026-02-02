package com.agent.api.controller;

import com.agent.api.response.ApiResponse;
import com.agent.api.request.SessionCreateRequest;
import com.agent.infrastructure.persistence.entity.SessionAgent;
import com.agent.domain.orchestration.graph.AgentGraphRunner;
import com.agent.service.SessionService;
import com.agent.service.SessionStopService;
import com.agent.infrastructure.persistence.entity.Session;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 会话管理控制器
 * 提供会话的 CRUD 操作和 SSE 流式响应
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SessionController {

    private final SessionService sessionService;
    private final SessionStopService sessionStopService;
    private final AgentGraphRunner graphRunner;

    /**
     * 获取所有会话
     */
    @GetMapping
    public ApiResponse<List<Session>> listSessions() {
        try {
            List<Session> sessions = sessionService.getAllSessions();
            return ApiResponse.success(sessions);
        } catch (Exception e) {
            log.error("List sessions failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 创建会话（数据库持久化版本）
     */
    @PostMapping("/create")
    public ApiResponse<Session> createPersistentSession(@Valid @RequestBody SessionCreateRequest request) {
        try {
            Session session = sessionService.createSession(request);
            return ApiResponse.success("会话创建成功", session);
        } catch (Exception e) {
            log.error("Create session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 更新会话状态
     */
    @PatchMapping("/{sessionId}/status")
    public ApiResponse<Session> updateSessionStatus(
            @PathVariable String sessionId,
            @RequestParam String status
    ) {
        try {
            Session session = sessionService.updateSessionStatus(sessionId, status);
            return ApiResponse.success("会话状态更新成功", session);
        } catch (Exception e) {
            log.error("Update session status failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 根据类型查询会话
     */
    @GetMapping("/type/{type}")
    public ApiResponse<List<Session>> getSessionsByType(@PathVariable String type) {
        try {
            List<Session> sessions = sessionService.getSessionsByType(type);
            return ApiResponse.success(sessions);
        } catch (Exception e) {
            log.error("Get sessions by type failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 根据状态查询会话
     */
    @GetMapping("/status/{status}")
    public ApiResponse<List<Session>> getSessionsByStatus(@PathVariable String status) {
        try {
            List<Session> sessions = sessionService.getSessionsByStatus(status);
            return ApiResponse.success(sessions);
        } catch (Exception e) {
            log.error("Get sessions by status failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取会话详情（数据库版本）
     */
    @GetMapping("/{sessionId}/detail")
    public ApiResponse<Session> getSessionDetail(@PathVariable String sessionId) {
        try {
            Session session = sessionService.getSessionById(sessionId);
            return ApiResponse.success(session);
        } catch (Exception e) {
            log.error("Get session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取会话中的智能体
     */
    @GetMapping("/{sessionId}/agents")
    public ApiResponse<List<SessionAgent>> getSessionAgents(@PathVariable String sessionId) {
        try {
            List<SessionAgent> agents = sessionService.getSessionAgents(sessionId);
            return ApiResponse.success(agents);
        } catch (Exception e) {
            log.error("Get session agents failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 添加智能体到会话
     */
    @PostMapping("/{sessionId}/agents/{agentId}")
    public ApiResponse<Void> addAgentToSession(
            @PathVariable String sessionId,
            @PathVariable Long agentId
    ) {
        try {
            sessionService.addAgentToSession(sessionId, agentId);
            return ApiResponse.success("智能体添加成功", null);
        } catch (Exception e) {
            log.error("Add agent to session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        try {
            sessionService.deleteSession(sessionId);
            return ApiResponse.success("会话删除成功", null);
        } catch (Exception e) {
            log.error("Delete session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 停止会话流输出
     * 用于用户主动停止 AI 生成
     */
    @PostMapping("/{sessionId}/stop")
    public ApiResponse<Void> stopSession(@PathVariable String sessionId) {
        try {
            // 标记停止标志，让后端流检测并中断
            sessionStopService.markStop(sessionId);

            // 更新会话状态为 PAUSED，表示用户主动停止
            sessionService.updateSessionStatus(sessionId, "PAUSED");

            log.info("Session stopped by user: {}", sessionId);
            return ApiResponse.success("会话已停止", null);
        } catch (Exception e) {
            log.error("Stop session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 聊天接口（SSE 流式响应）
     */
    @PostMapping("/{sessionId}/chat")
    public Flux<ServerSentEvent<String>> chat(
            @PathVariable String sessionId,
            @RequestBody UserInputRequest request) {
        
        String requestId = UUID.randomUUID().toString();
        return graphRunner.submit(sessionId, requestId, request.getMessage());
    }
    
    /**
     * 恢复执行（人工回答后）
     */
    @PostMapping("/{sessionId}/resume")
    public Flux<ServerSentEvent<String>> resume(
            @PathVariable String sessionId,
            @RequestBody UserInputRequest request) {
        
        return graphRunner.resume(sessionId, request.getMessage());
    }
    
    /**
     * 用户输入请求 DTO
     */
    @Data
    public static class UserInputRequest {
        private String message;
    }
}
