package com.agent.controller;

import com.agent.application.orchestration.StateManager;
import com.agent.domain.model.plan.ExecutionPlan;
import com.agent.domain.model.plan.PlanStatus;
import com.agent.dto.SubmitResponse;
import com.agent.dto.UserInputRequest;
import com.agent.dto.WorkflowStateResponse;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.OrchestrationEvent;
import com.agent.infrastructure.event.RedisStreamEventBus;
import com.agent.infrastructure.graph.AgentGraphRunner;
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
 * 基于 Graph 的多智能体编排 API
 *
 * API:
 * - POST /group-chat/{sessionId}/submit  - 提交用户输入
 * - GET  /group-chat/{sessionId}/events  - SSE 事件流
 * - POST /group-chat/{sessionId}/resume  - 恢复执行（用户回答问题后）
 * - GET  /group-chat/{sessionId}/state   - 查询当前状态
 * - POST /group-chat/{sessionId}/message - 智能路由（submit/resume）
 * - POST /group-chat/{sessionId}/stop    - 停止执行
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GroupChatController {

    private final AgentGraphRunner graphRunner;
    private final StateManager stateManager;
    private final EventBus eventBus;
    private final SessionStopService sessionStopService;

    /**
     * 处理用户消息（智能路由）
     * 根据当前状态自动判断是 submit 还是 resume
     */
    @PostMapping("/group-chat/{sessionId}/message")
    public ResponseEntity<SubmitResponse> handleUserMessage(
            @PathVariable String sessionId,
            @RequestBody UserInputRequest request) {

        log.info("[API] Handle user message: sessionId={}", sessionId);

        try {
            ExecutionPlan plan = stateManager.loadPlan(sessionId);
            String requestId = UUID.randomUUID().toString();

            if (plan != null && plan.getStatus() == PlanStatus.PAUSED) {
                log.info("[API] Session is PAUSED, routing to RESUME");
                graphRunner.resume(sessionId, request.text()).subscribe();
                return ResponseEntity.ok(SubmitResponse.resumed(requestId));
            } else {
                log.info("[API] Session is IDLE/NEW, routing to SUBMIT");
                graphRunner.submit(sessionId, requestId, request.text()).subscribe();
                return ResponseEntity.ok(SubmitResponse.processing(requestId));
            }

        } catch (Exception e) {
            log.error("[API] Message handling failed", e);
            return ResponseEntity.internalServerError()
                    .body(SubmitResponse.error(UUID.randomUUID().toString(), e.getMessage()));
        }
    }

    /**
     * 提交用户输入
     */
    @PostMapping("/group-chat/{sessionId}/submit")
    public ResponseEntity<SubmitResponse> submit(
            @PathVariable String sessionId,
            @RequestBody UserInputRequest request) {

        log.info("[API] Submit user input: sessionId={}, text={}", sessionId, request.text());

        try {
            String requestId = UUID.randomUUID().toString();
            graphRunner.submit(sessionId, requestId, request.text()).subscribe();
            return ResponseEntity.ok(SubmitResponse.processing(requestId));
        } catch (Exception e) {
            log.error("[API] Submit failed: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError()
                    .body(SubmitResponse.error(UUID.randomUUID().toString(), e.getMessage()));
        }
    }

    /**
     * SSE 事件流 - 支持断点续传
     */
    @GetMapping(value = "/group-chat/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(
            @PathVariable String sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        log.info("[API] SSE subscribe: sessionId={}, lastEventId={}", sessionId, lastEventId);

        Flux<OrchestrationEvent> eventFlux;
        if (eventBus instanceof RedisStreamEventBus streamEventBus) {
            eventFlux = streamEventBus.subscribe(sessionId, lastEventId);
        } else {
            eventFlux = eventBus.subscribe(sessionId);
        }

        return eventFlux
                .map(event -> ServerSentEvent.<String>builder()
                        .id(event.getStreamRecordId())
                        .event(event.getEventType())
                        .data(event.toJson())
                        .build())
                .doOnSubscribe(s -> log.info("[API] SSE connected: sessionId={}", sessionId))
                .doOnCancel(() -> log.info("[API] SSE disconnected: sessionId={}", sessionId))
                .onErrorResume(e -> {
                    log.debug("[API] SSE error handled: sessionId={}", sessionId);
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
            String requestId = UUID.randomUUID().toString();
            graphRunner.resume(sessionId, request.text()).subscribe();
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

        ExecutionPlan plan = stateManager.loadPlan(sessionId);
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
