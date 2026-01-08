package com.agent.controller;

import com.agent.application.orchestration.WorkflowEngine;
import com.agent.domain.model.plan.ExecutionPlan;
import com.agent.dto.SubmitResponse;
import com.agent.dto.UserInputRequest;
import com.agent.dto.WorkflowStateResponse;
import com.agent.infrastructure.event.EventBus;
import com.agent.service.SessionStopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 群聊控制器
 * 提供基于异步事件驱动的多智能体群聊 API
 *
 * 新架构（异步模式）:
 * - POST /group-chat/{sessionId}/submit  - 提交用户输入，立即返回
 * - GET  /group-chat/{sessionId}/events  - SSE 事件流（独立连接）
 * - POST /group-chat/{sessionId}/resume  - 恢复执行（用户回答问题后）
 * - GET  /group-chat/{sessionId}/state   - 查询当前状态
 *
 * 旧接口（兼容保留，内部使用新架构）:
 * - POST /group-chat - SSE 流
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GroupChatController {

    private final WorkflowEngine workflowEngine;
    private final EventBus eventBus;
    private final SessionStopService sessionStopService;

    // ==================== 新架构 API（异步模式）====================

    /**
     * 提交用户输入 - Fire and Forget
     * 立即返回 requestId，前端用它来订阅事件
     */
    @PostMapping("/group-chat/{sessionId}/submit")
    public ResponseEntity<SubmitResponse> submit(
            @PathVariable String sessionId,
            @RequestBody UserInputRequest request) {

        log.info("[API] Submit user input: sessionId={}, text={}", sessionId, request.text());

        try {
            String requestId = workflowEngine.submitUserInput(sessionId, request.text());
            return ResponseEntity.ok(SubmitResponse.processing(requestId));
        } catch (Exception e) {
            log.error("[API] Submit failed: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError()
                    .body(SubmitResponse.error(UUID.randomUUID().toString(), e.getMessage()));
        }
    }

    /**
     * SSE 事件流 - 独立连接
     * 前端调用此接口订阅事件，与 submit 完全解耦
     */
    @GetMapping(value = "/group-chat/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(@PathVariable String sessionId) {
        log.info("[API] SSE subscribe: sessionId={}", sessionId);

        return eventBus.subscribe(sessionId)
                .map(event -> ServerSentEvent.<String>builder()
                        .event(event.getEventType())
                        .data(event.toJson())
                        .build())
                .doOnSubscribe(s -> log.info("[API] SSE connected: sessionId={}", sessionId))
                .doOnCancel(() -> {
                    log.info("[API] SSE disconnected: sessionId={}", sessionId);
                    // SSE 断开不影响后台任务继续执行
                })
                .doOnError(e -> {
                    // 记录错误但不抛出，避免 AsyncContext 竞态条件
                    if (!e.getMessage().contains("AsyncContext")) {
                        log.warn("[API] SSE error: sessionId={}, error={}", sessionId, e.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    // 优雅处理错误，返回空 Flux 而不是让错误传播
                    log.debug("[API] SSE stream error handled gracefully: sessionId={}", sessionId);
                    return Flux.empty();
                });
    }

    /**
     * 恢复执行（用户回答问题后）
     */
    @PostMapping("/group-chat/{sessionId}/resume")
    public ResponseEntity<SubmitResponse> resume(
            @PathVariable String sessionId,
            @RequestBody UserInputRequest request) {

        log.info("[API] Resume session: sessionId={}, answer={}", sessionId, request.text());

        try {
            String requestId = workflowEngine.resume(sessionId, request.text());
            return ResponseEntity.ok(SubmitResponse.resumed(requestId));
        } catch (Exception e) {
            log.error("[API] Resume failed: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError()
                    .body(SubmitResponse.error(UUID.randomUUID().toString(), e.getMessage()));
        }
    }

    /**
     * 查询当前状态
     */
    @GetMapping("/group-chat/{sessionId}/state")
    public ResponseEntity<WorkflowStateResponse> getState(@PathVariable String sessionId) {
        log.info("[API] Get state: sessionId={}", sessionId);

        ExecutionPlan plan = workflowEngine.getState(sessionId);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(WorkflowStateResponse.from(plan));
    }

    /**
     * 停止当前执行
     */
    @PostMapping("/group-chat/{sessionId}/stop")
    public ResponseEntity<Void> stop(@PathVariable String sessionId) {
        log.info("[API] Stop session: sessionId={}", sessionId);
        sessionStopService.markStop(sessionId);
        return ResponseEntity.ok().build();
    }
}
