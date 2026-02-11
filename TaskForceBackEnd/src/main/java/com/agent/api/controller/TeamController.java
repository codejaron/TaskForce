package com.agent.api.controller;

import com.agent.api.response.ApiResponse;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.infrastructure.event.EventBus;
import com.agent.service.TeamOrchestrationService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Team 编排控制器
 * 提供 Team Lead 和 Worker 协作的 API 入口
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/team")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TeamController {

    private final TeamOrchestrationService teamOrchestrationService;
    private final WorkerInstanceManager workerInstanceManager;
    private final TaskBoardService taskBoardService;
    private final EventBus eventBus;

    /**
     * 启动团队会话
     * POST /api/v2/team/session/start
     */
    @PostMapping("/session/start")
    public ApiResponse<Void> startTeamSession(@Valid @RequestBody TeamStartRequest request) {
        log.info("[TeamController] Starting team session: sessionId={}, goal={}",
                 request.getSessionId(), request.getUserGoal());

        try {
            // 异步启动团队会话，事件通过 EventBus 发送到 /session/{sessionId}/events
            new Thread(() -> {
                teamOrchestrationService.startTeamSession(
                        request.getSessionId(),
                        request.getUserGoal()
                );
            }, "team-start-" + request.getSessionId()).start();

            return ApiResponse.success("团队会话启动中", null);
        } catch (Exception e) {
            log.error("[TeamController] Failed to start team session", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 用户向 Team Lead 发送消息
     * POST /api/v2/team/session/{sessionId}/lead/message
     */
    @PostMapping("/session/{sessionId}/lead/message")
    public ApiResponse<Void> sendMessageToLead(
            @PathVariable String sessionId,
            @RequestBody MessageRequest request) {

        log.info("[TeamController] Sending message to Lead: sessionId={}, message={}",
                 sessionId, request.getMessage());

        try {
            teamOrchestrationService.sendMessageToLead(sessionId, request.getMessage());
            return ApiResponse.success("消息已发送给 Team Lead", null);
        } catch (Exception e) {
            log.error("[TeamController] Failed to send message to Lead", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 用户向指定 Worker 发送消息
     * POST /api/v2/team/session/{sessionId}/worker/{instanceId}/message
     */
    @PostMapping("/session/{sessionId}/worker/{instanceId}/message")
    public ApiResponse<Void> sendMessageToWorker(
            @PathVariable String sessionId,
            @PathVariable String instanceId,
            @RequestBody MessageRequest request) {

        log.info("[TeamController] Sending message to Worker: sessionId={}, instanceId={}, message={}",
                 sessionId, instanceId, request.getMessage());

        try {
            teamOrchestrationService.sendMessageToWorker(sessionId, instanceId, request.getMessage());
            return ApiResponse.success("消息已发送给 Worker", null);
        } catch (Exception e) {
            log.error("[TeamController] Failed to send message to Worker", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 列出会话中的所有 Worker
     * GET /api/v2/team/session/{sessionId}/workers
     */
    @GetMapping("/session/{sessionId}/workers")
    public ApiResponse<List<WorkerInstance>> listWorkers(@PathVariable String sessionId) {
        log.info("[TeamController] Listing workers: sessionId={}", sessionId);

        try {
            List<WorkerInstance> workers = workerInstanceManager.getRunningWorkers(sessionId);
            return ApiResponse.success(workers);
        } catch (Exception e) {
            log.error("[TeamController] Failed to list workers", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取任务板
     * GET /api/v2/team/session/{sessionId}/taskboard
     */
    @GetMapping("/session/{sessionId}/taskboard")
    public ApiResponse<List<Task>> getTaskBoard(@PathVariable String sessionId) {
        log.info("[TeamController] Getting task board: sessionId={}", sessionId);

        try {
            List<Task> tasks = taskBoardService.listTasks(sessionId);
            return ApiResponse.success(tasks);
        } catch (Exception e) {
            log.error("[TeamController] Failed to get task board", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 订阅团队事件流（SSE）
     * GET /api/v2/team/session/{sessionId}/events
     */
    @GetMapping("/session/{sessionId}/events")
    public Flux<ServerSentEvent<String>> subscribeTeamEvents(@PathVariable String sessionId) {
        log.info("[TeamController] Subscribing to team events: sessionId={}", sessionId);

        try {
            // 订阅 session 级别的事件通道
            return eventBus.subscribe(sessionId)
                    .map(event -> ServerSentEvent.<String>builder()
                            .event(event.getEventType())
                            .data(event.toString())
                            .build())
                    .doOnCancel(() -> {
                        log.info("[TeamController] Team event subscription cancelled: {}", sessionId);
                        eventBus.unsubscribe(sessionId);
                    })
                    .doOnError(e -> {
                        log.error("[TeamController] Team event stream error: {}", sessionId, e);
                        eventBus.unsubscribe(sessionId);
                    });
        } catch (Exception e) {
            log.error("[TeamController] Failed to subscribe to team events", e);
            return Flux.error(e);
        }
    }

    /**
     * 订阅 Worker 事件流（SSE）
     * GET /api/v2/team/session/{sessionId}/worker/{instanceId}/events
     */
    @GetMapping("/session/{sessionId}/worker/{instanceId}/events")
    public Flux<ServerSentEvent<String>> subscribeWorkerEvents(
            @PathVariable String sessionId,
            @PathVariable String instanceId) {

        log.info("[TeamController] Subscribing to Worker events: sessionId={}, instanceId={}",
                 sessionId, instanceId);

        try {
            // 订阅 Worker 专属事件通道
            String workerChannel = sessionId + ":" + instanceId;
            return eventBus.subscribe(workerChannel)
                    .map(event -> ServerSentEvent.<String>builder()
                            .event(event.getEventType())
                            .data(event.toString())
                            .build())
                    .doOnCancel(() -> {
                        log.info("[TeamController] Worker event subscription cancelled: {}", instanceId);
                        eventBus.unsubscribe(workerChannel);
                    })
                    .doOnError(e -> {
                        log.error("[TeamController] Worker event stream error: {}", instanceId, e);
                        eventBus.unsubscribe(workerChannel);
                    });
        } catch (Exception e) {
            log.error("[TeamController] Failed to subscribe to Worker events", e);
            return Flux.error(e);
        }
    }

    /**
     * 停止团队会话
     * POST /api/v2/team/session/{sessionId}/stop
     */
    @PostMapping("/session/{sessionId}/stop")
    public ApiResponse<Void> stopTeamSession(@PathVariable String sessionId) {
        log.info("[TeamController] Stopping team session: sessionId={}", sessionId);

        try {
            teamOrchestrationService.stopSession(sessionId);
            return ApiResponse.success("团队会话已停止", null);
        } catch (Exception e) {
            log.error("[TeamController] Failed to stop team session", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 团队启动请求 DTO
     */
    @Data
    public static class TeamStartRequest {
        private String sessionId;
        private String userGoal;
    }

    /**
     * 消息请求 DTO
     */
    @Data
    public static class MessageRequest {
        private String message;
    }
}
