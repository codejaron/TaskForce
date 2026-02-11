package com.agent.mcpserver.service;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolVO;
import com.agent.mcpserver.protocol.JsonRpcRequest;
import com.agent.mcpserver.protocol.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MCP 协议处理器
 * 处理 JSON-RPC 请求并返回响应
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpProtocolHandler {

    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // MCP 协议版本
    private static final String MCP_VERSION = "2024-11-05";

    /**
     * 处理 JSON-RPC 请求
     */
    public JsonRpcResponse handleRequest(JsonRpcRequest request, String sessionId) {
        String method = request.getMethod();
        Object id = request.getId();
        Map<String, Object> params = request.getParams();

        log.debug("[McpProtocol] Handling request: method={}, id={}", method, id);

        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "initialized" -> handleInitialized(params);
                case "tools/list" -> handleToolsList(params);
                case "tools/call" -> handleToolsCall(params, sessionId);
                case "ping" -> handlePing();
                default -> throw new UnsupportedOperationException("Method not supported: " + method);
            };

            return JsonRpcResponse.success(id, result);

        } catch (UnsupportedOperationException e) {
            log.warn("[McpProtocol] Unsupported method: {}", method);
            return JsonRpcResponse.error(id, JsonRpcResponse.METHOD_NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("[McpProtocol] Invalid params: {}", e.getMessage());
            return JsonRpcResponse.error(id, JsonRpcResponse.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            log.error("[McpProtocol] Internal error", e);
            return JsonRpcResponse.error(id, JsonRpcResponse.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * 处理 initialize 请求
     */
    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", MCP_VERSION);

        // Server capabilities
        Map<String, Object> capabilities = new LinkedHashMap<>();
        
        // Tools capability
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("listChanged", true);
        capabilities.put("tools", tools);

        result.put("capabilities", capabilities);

        // Server info
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "unified-mcp-server");
        serverInfo.put("version", "1.0.0");
        result.put("serverInfo", serverInfo);

        log.info("[McpProtocol] Initialize completed");
        return result;
    }

    /**
     * 处理 initialized 通知
     */
    private Map<String, Object> handleInitialized(Map<String, Object> params) {
        log.info("[McpProtocol] Client initialized");
        return Map.of();
    }

    /**
     * 处理 tools/list 请求
     */
    private Map<String, Object> handleToolsList(Map<String, Object> params) {
        List<ToolVO> tools = toolRouter.listAllTools();

        // 转换为 MCP 协议格式
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (ToolVO tool : tools) {
            Map<String, Object> toolMap = new LinkedHashMap<>();
            toolMap.put("name", tool.getName());
            toolMap.put("description", tool.getDescription() != null ? tool.getDescription() : "");
            
            if (tool.getInputSchema() != null) {
                toolMap.put("inputSchema", tool.getInputSchema());
            } else {
                // 默认的空 schema
                Map<String, Object> defaultSchema = new LinkedHashMap<>();
                defaultSchema.put("type", "object");
                defaultSchema.put("properties", Map.of());
                toolMap.put("inputSchema", defaultSchema);
            }

            toolList.add(toolMap);
        }

        log.info("[McpProtocol] tools/list returned {} tools", toolList.size());
        return Map.of("tools", toolList);
    }

    /**
     * 处理 tools/call 请求
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Map<String, Object> params, String sessionId) {
        if (params == null) {
            throw new IllegalArgumentException("Missing params");
        }

        String name = (String) params.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Missing tool name");
        }

        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        log.info("[McpProtocol] tools/call: name={}, sessionId={}", name, sessionId);

        // 调用工具
        ToolCallResult result = toolRouter.callTool(name, arguments, sessionId);

        // 转换为 MCP 协议格式
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", result.getContent());
        response.put("isError", result.getIsError());

        return response;
    }

    /**
     * 处理 ping 请求
     */
    private Map<String, Object> handlePing() {
        return Map.of();
    }
}
