package com.agent.mcpserver.controller;

import com.agent.mcpserver.protocol.JsonRpcRequest;
import com.agent.mcpserver.protocol.JsonRpcResponse;
import com.agent.mcpserver.service.McpProtocolHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * MCP Streamable HTTP 控制器
 * 提供统一的 HTTP POST 接口，接收和处理 JSON-RPC 请求
 * 
 * 与 McpSseController 不同：
 * - 不需要建立 SSE 连接
 * - 直接通过 HTTP POST 发送 JSON-RPC 请求
 * - 同步返回 JSON-RPC 响应
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class McpStreamableHttpController {

    private final McpProtocolHandler protocolHandler;

    /**
     * Streamable HTTP 端点
     * POST /mcp -> 接收 JSON-RPC 请求并返回响应
     * 
     * 请求格式:
     * {
     *   "jsonrpc": "2.0",
     *   "method": "tools/call",
     *   "params": {
     *     "name": "toolName",
     *     "arguments": {}
     *   },
     *   "id": "uuid"
     * }
     * 
     * 响应格式:
     * {
     *   "jsonrpc": "2.0",
     *   "id": "uuid",
     *   "result": { ... }
     * }
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<JsonRpcResponse> handleRequest(
            @RequestParam(required = false) String sessionId,
            @RequestBody JsonRpcRequest request
    ) {
        log.info("[StreamableHTTP] Received request: method={}, id={}, sessionId={}", 
                request.getMethod(), request.getId(), sessionId);

        try {
            // 处理请求
            JsonRpcResponse response = protocolHandler.handleRequest(request, sessionId);
            
            log.debug("[StreamableHTTP] Returning response: id={}", response.getId());
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[StreamableHTTP] Error processing request", e);
            return ResponseEntity.ok(JsonRpcResponse.error(
                    request.getId(),
                    JsonRpcResponse.INTERNAL_ERROR,
                    "Internal error: " + e.getMessage()
            ));
        }
    }
}
