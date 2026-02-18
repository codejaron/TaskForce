package com.agent.api.controller;

import com.agent.api.dto.TeamSessionHistoryDTO;
import com.agent.api.response.ApiResponse;
import com.agent.domain.execution.service.SessionOwnerService;
import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.lead.TeamLeadAgent;
import com.agent.domain.team.service.TeamHistoryPersistenceService;
import com.agent.domain.team.service.TeamHistoryQueryService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.OrchestrationEvent;
import com.agent.infrastructure.event.RedisStreamEventBus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agent.service.TeamOwnerForwardService;
import com.agent.service.TeamOrchestrationService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final TeamHistoryPersistenceService teamHistoryPersistenceService;
    private final TeamHistoryQueryService teamHistoryQueryService;
    private final TeamLeadAgent teamLeadAgent;
    private final AgentExecutionStateService executionStateService;
    private final SessionOwnerService sessionOwnerService;
    private final TeamOwnerForwardService teamOwnerForwardService;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    /**
     * 启动团队会话
     * POST /api/v2/team/session/start
     */
    @PostMapping("/session/start")
    public ApiResponse<Void> startTeamSession(@Valid @RequestBody TeamStartRequest request) {
        log.info("[TeamController] Starting team session: sessionId={}, goal={}",
                 request.getSessionId(), request.getUserGoal());

        try {
            boolean acquired = sessionOwnerService.tryAcquireOwner(request.getSessionId());
            if (!acquired) {
                String owner = sessionOwnerService.getOwnerNode(request.getSessionId()).orElse("unknown");
                log.info("[TeamController] Start rejected by owner guard: sessionId={}, owner={}",
                        request.getSessionId(), owner);
                return ApiResponse.error(409, "会话已在其他节点运行: " + owner);
            }

            teamHistoryPersistenceService.persistUserMessage(request.getSessionId(), request.getUserGoal());

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
            String owner = sessionOwnerService.getOwnerNode(sessionId).orElse(null);
            if (owner != null && !sessionOwnerService.isCurrentNode(owner)) {
                return teamOwnerForwardService.forward(
                        owner,
                        HttpMethod.POST,
                        "/api/v2/team/session/" + sessionId + "/lead/message",
                        request,
                        Void.class
                );
            }
            teamOrchestrationService.sendMessageToLead(sessionId, request.getMessage());
            teamHistoryPersistenceService.persistUserMessage(sessionId, request.getMessage());
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
            String owner = sessionOwnerService.getOwnerNode(sessionId).orElse(null);
            if (owner != null && !sessionOwnerService.isCurrentNode(owner)) {
                return teamOwnerForwardService.forward(
                        owner,
                        HttpMethod.POST,
                        "/api/v2/team/session/" + sessionId + "/worker/" + instanceId + "/message",
                        request,
                        Void.class
                );
            }
            teamOrchestrationService.sendMessageToWorker(sessionId, instanceId, request.getMessage());
            teamHistoryPersistenceService.persistUserMessageToWorker(sessionId, instanceId, request.getMessage());
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
    public ApiResponse<List<WorkerRuntimeDTO>> listWorkers(@PathVariable String sessionId) {
        log.info("[TeamController] Listing workers: sessionId={}", sessionId);

        try {
            List<WorkerRuntimeDTO> workers = workerInstanceManager.getAllWorkers(sessionId).stream()
                    .map(this::buildWorkerRuntimeDto)
                    .toList();
            return ApiResponse.success(workers);
        } catch (Exception e) {
            log.error("[TeamController] Failed to list workers", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取 Lead + Worker 运行时状态（运行/停止/销毁）
     * GET /api/v2/team/session/{sessionId}/runtime-status
     */
    @GetMapping("/session/{sessionId}/runtime-status")
    public ApiResponse<TeamRuntimeStatusDTO> getRuntimeStatus(@PathVariable String sessionId) {
        try {
            String owner = sessionOwnerService.getOwnerNode(sessionId).orElse(null);
            if (owner != null && !sessionOwnerService.isCurrentNode(owner)) {
                return teamOwnerForwardService.forward(
                        owner,
                        HttpMethod.GET,
                        "/api/v2/team/session/" + sessionId + "/runtime-status",
                        null,
                        TeamRuntimeStatusDTO.class
                );
            }
            String leadInstanceId = sessionId + "_lead";
            AgentExecutionStatus leadExecutionStatus = executionStateService.getStatus(leadInstanceId);
            boolean leadLoopRunning = teamLeadAgent.isLeadLoopRunning(sessionId);

            List<WorkerRuntimeDTO> workers = workerInstanceManager.getAllWorkers(sessionId).stream()
                    .map(this::buildWorkerRuntimeDto)
                    .toList();

            TeamRuntimeStatusDTO runtimeStatus = TeamRuntimeStatusDTO.builder()
                    .leadLifecycleStatus(mapLeadLifecycleStatus(leadExecutionStatus, leadLoopRunning))
                    .leadLoopRunning(leadLoopRunning)
                    .leadExecutionStatus(leadExecutionStatus == null ? null : leadExecutionStatus.name())
                    .workers(workers)
                    .build();

            return ApiResponse.success(runtimeStatus);
        } catch (Exception e) {
            log.error("[TeamController] Failed to get runtime status: sessionId={}", sessionId, e);
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
     * 查询 Team 会话历史（数据库基线数据）。
     * GET /api/v2/team/session/{sessionId}/history?limit=200&before=2026-02-14T10:00:00
     */
    @GetMapping("/session/{sessionId}/history")
    public ApiResponse<TeamSessionHistoryDTO> getHistory(
            @PathVariable String sessionId,
            @RequestParam(value = "limit", required = false, defaultValue = "200") Integer limit,
            @RequestParam(value = "before", required = false) String before) {
        log.info("[TeamController] Getting team history: sessionId={}, limit={}, before={}", sessionId, limit, before);
        try {
            TeamSessionHistoryDTO history = teamHistoryQueryService.getHistory(sessionId, limit, before);
            return ApiResponse.success(history);
        } catch (Exception e) {
            log.error("[TeamController] Failed to get team history", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 订阅团队事件流（SSE）- 支持断点续传
     * GET /api/v2/team/session/{sessionId}/events
     */
    @GetMapping(value = "/session/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribeTeamEvents(
            @PathVariable String sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        log.info("[TeamController] SSE subscribe: sessionId={}, lastEventId={}", sessionId, lastEventId);

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
                        .data(buildSseData(event))
                        .build())
                .doOnSubscribe(s -> log.info("[TeamController] SSE connected: sessionId={}", sessionId))
                .doOnCancel(() -> log.info("[TeamController] SSE disconnected: sessionId={}", sessionId))
                .onErrorResume(e -> {
                    log.debug("[TeamController] SSE error handled: sessionId={}", sessionId);
                    return Flux.empty();
                });
    }

    /**
     * 订阅 Worker 事件流（SSE）- 支持断点续传
     * GET /api/v2/team/session/{sessionId}/worker/{instanceId}/events
     */
    @GetMapping(value = "/session/{sessionId}/worker/{instanceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribeWorkerEvents(
            @PathVariable String sessionId,
            @PathVariable String instanceId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        log.info("[TeamController] SSE subscribe worker: sessionId={}, instanceId={}, lastEventId={}",
                sessionId, instanceId, lastEventId);

        Flux<OrchestrationEvent> eventFlux;
        if (eventBus instanceof RedisStreamEventBus streamEventBus) {
            eventFlux = streamEventBus.subscribeWorker(sessionId, instanceId, lastEventId);
        } else {
            eventFlux = eventBus.subscribeWorker(sessionId, instanceId);
        }

        return eventFlux
                .map(event -> ServerSentEvent.<String>builder()
                        .id(event.getStreamRecordId())
                        .event(event.getEventType())
                        .data(buildSseData(event))
                        .build())
                .doOnSubscribe(s -> log.info("[TeamController] Worker SSE connected: sessionId={}, instanceId={}",
                        sessionId, instanceId))
                .doOnCancel(() -> log.info("[TeamController] Worker SSE disconnected: sessionId={}, instanceId={}",
                        sessionId, instanceId))
                .onErrorResume(e -> {
                    log.debug("[TeamController] Worker SSE error handled: sessionId={}, instanceId={}",
                            sessionId, instanceId);
                    return Flux.empty();
                });
    }

    /**
     * 停止团队会话
     * POST /api/v2/team/session/{sessionId}/stop
     */
    @PostMapping("/session/{sessionId}/stop")
    public ApiResponse<Void> stopTeamSession(@PathVariable String sessionId) {
        log.info("[TeamController] Stopping team session: sessionId={}", sessionId);

        try {
            String owner = sessionOwnerService.getOwnerNode(sessionId).orElse(null);
            if (owner != null && !sessionOwnerService.isCurrentNode(owner)) {
                return teamOwnerForwardService.forward(
                        owner,
                        HttpMethod.POST,
                        "/api/v2/team/session/" + sessionId + "/stop",
                        null,
                        Void.class
                );
            }
            teamOrchestrationService.stopSession(sessionId);
            sessionOwnerService.releaseOwner(sessionId);
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

    @Data
    @lombok.Builder
    public static class WorkerRuntimeDTO {
        private String instanceId;
        private Integer workerId;
        private String agentName;
        private String status;
        private Integer currentTaskId;
        private Boolean loopRunning;
        private String lifecycleStatus;
        private String startedAt;
        private String updatedAt;
    }

    @Data
    @lombok.Builder
    public static class TeamRuntimeStatusDTO {
        private String leadLifecycleStatus;
        private Boolean leadLoopRunning;
        private String leadExecutionStatus;
        private List<WorkerRuntimeDTO> workers;
    }

    private String buildSseData(OrchestrationEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    event.toJson(),
                    new TypeReference<Map<String, Object>>() {}
            );
            payload.put("eventType", event.getEventType());
            if (event.getStreamRecordId() != null && !event.getStreamRecordId().isBlank()) {
                payload.put("streamRecordId", event.getStreamRecordId());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[TeamController] Failed to enrich SSE payload: eventType={}", event.getEventType(), e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("eventType", event.getEventType());
            fallback.put("raw", event.toJson());
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"eventType\":\"" + event.getEventType() + "\"}";
            }
        }
    }

    private WorkerRuntimeDTO buildWorkerRuntimeDto(WorkerInstance worker) {
        boolean loopRunning = workerInstanceManager.isRunning(worker.getInstanceId());
        String lifecycleStatus;
        if (worker.isShutdown()) {
            lifecycleStatus = "DESTROYED";
        } else if (loopRunning) {
            lifecycleStatus = "RUNNING";
        } else {
            lifecycleStatus = "STOPPED";
        }

        return WorkerRuntimeDTO.builder()
                .instanceId(worker.getInstanceId())
                .workerId(worker.getWorkerId())
                .agentName(worker.getName())
                .status(worker.getStatus() == null ? null : worker.getStatus().name())
                .currentTaskId(worker.getCurrentTaskId())
                .loopRunning(loopRunning)
                .lifecycleStatus(lifecycleStatus)
                .startedAt(worker.getStartedAt() == null ? null : worker.getStartedAt().toString())
                .updatedAt(worker.getUpdatedAt() == null ? null : worker.getUpdatedAt().toString())
                .build();
    }

    private String mapLeadLifecycleStatus(AgentExecutionStatus leadExecutionStatus, boolean leadLoopRunning) {
        if (leadExecutionStatus == AgentExecutionStatus.COMPLETED || leadExecutionStatus == AgentExecutionStatus.FAILED) {
            return "DESTROYED";
        }
        if (leadLoopRunning || leadExecutionStatus == AgentExecutionStatus.EXECUTING || leadExecutionStatus == AgentExecutionStatus.RUNNING) {
            return "RUNNING";
        }
        return "STOPPED";
    }
}
