package com.agent.mcpserver.controller;

import com.agent.mcpserver.protocol.JsonRpcRequest;
import com.agent.mcpserver.protocol.JsonRpcResponse;
import com.agent.mcpserver.service.McpProtocolHandler;
import com.agent.mcpserver.service.McpSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * MCP SSE 接口控制器
 * 提供统一的 SSE 长连接和消息处理接口
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class McpSseController {

    private final McpSessionManager sessionManager;
    private final McpProtocolHandler protocolHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * SSE 长连接端点
     * GET /sse -> 建立 SSE 连接
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> sseConnect() {
        // 创建新会话
        McpSessionManager.SessionInfo session = sessionManager.createSession();
        String sessionId = session.sessionId();
        
        log.info("[SSE] New connection established: {}", sessionId);

        // 发送初始化事件（包含 message endpoint）
        String initEvent = formatSseEvent("endpoint", Map.of("endpoint", session.messageEndpoint()));
        
        // 合并初始化事件和后续事件流
        Flux<String> initFlux = Flux.just(initEvent);
        Flux<String> eventFlux = session.eventSink().asFlux()
                .map(data -> formatSseEvent("message", data));

        // 添加心跳
        Flux<String> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .map(i -> formatSseEvent("ping", Map.of("timestamp", System.currentTimeMillis())));

        return Flux.merge(initFlux, eventFlux, heartbeat)
                .doOnCancel(() -> {
                    log.info("[SSE] Connection cancelled: {}", sessionId);
                    sessionManager.closeSession(sessionId);
                })
                .doOnError(e -> {
                    log.error("[SSE] Connection error: {} - {}", sessionId, e.getMessage());
                    sessionManager.closeSession(sessionId);
                })
                .doOnComplete(() -> {
                    log.info("[SSE] Connection completed: {}", sessionId);
                    sessionManager.closeSession(sessionId);
                });
    }

    /**
     * 消息处理端点
     * POST /message -> 接收请求，路由到对应工具
     */
    @PostMapping(value = "/message", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonRpcResponse> handleMessage(
            @RequestParam(required = false) String sessionId,
            @RequestBody JsonRpcRequest request
    ) {
        log.info("[Message] Received request: method={}, sessionId={}", request.getMethod(), sessionId);

        try {
            // 处理请求
            JsonRpcResponse response = protocolHandler.handleRequest(request, sessionId);

            // 如果有活跃的 SSE 会话，推送响应
            if (sessionId != null && sessionManager.isSessionActive(sessionId)) {
                String eventData = objectMapper.writeValueAsString(response);
                sessionManager.sendEvent(sessionId, eventData);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Message] Error processing request", e);
            return ResponseEntity.ok(JsonRpcResponse.error(
                    request.getId(),
                    JsonRpcResponse.INTERNAL_ERROR,
                    e.getMessage()
            ));
        }
    }

    /**
     * 格式化 SSE 事件
     */
    private String formatSseEvent(String event, Object data) {
        try {
            String dataJson = data instanceof String ? (String) data : objectMapper.writeValueAsString(data);
            return "event: " + event + "\ndata: " + dataJson + "\n\n";
        } catch (Exception e) {
            return "event: error\ndata: {\"error\":\"" + e.getMessage() + "\"}\n\n";
        }
    }
}
