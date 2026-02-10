package com.agent.infrastructure.mcp;

import com.agent.common.dto.ToolInfo;
import com.agent.service.SessionExecutionTracker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;

/**
 * 远程 MCP 客户端
 * 通过 HTTP 调用 mcp-server 服务
 */
@Slf4j
@Service
public class RemoteMcpClient {

    private static final String MCP_SERVICE_NAME = "mcp-server";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LoadBalancerClient loadBalancerClient;
    private final SessionExecutionTracker executionTracker;

    public RemoteMcpClient(ObjectMapper objectMapper, LoadBalancerClient loadBalancerClient, SessionExecutionTracker executionTracker) {
        this.objectMapper = objectMapper;
        this.loadBalancerClient = loadBalancerClient;
        this.executionTracker = executionTracker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 通过 Nacos 服务发现获取 mcp-server 的 URL
     * 支持重试机制
     * 使用 boundedElastic 线程池避免在 Reactor 事件循环中阻塞
     */
    private String getMcpServerUrl() {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < MAX_RETRY_ATTEMPTS) {
            try {
                ServiceInstance instance = loadBalancerClient.choose(MCP_SERVICE_NAME);
                if (instance == null) {
                    throw new RuntimeException("No available instance found for service: " + MCP_SERVICE_NAME);
                }
                String url = instance.getUri().toString();
                log.debug("Discovered mcp-server instance: {} (attempt {})", url, attempts + 1);
                return url;
            } catch (Exception e) {
                lastException = e;
                attempts++;
                if (attempts < MAX_RETRY_ATTEMPTS) {
                    log.warn("Failed to discover mcp-server (attempt {}/{}), retrying...", attempts, MAX_RETRY_ATTEMPTS);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        String errorMsg = String.format(
                "Failed to discover mcp-server after %d attempts. Please ensure mcp-server is running and registered in Nacos.",
                MAX_RETRY_ATTEMPTS
        );
        log.error(errorMsg, lastException);
        throw new RuntimeException(errorMsg, lastException);
    }

    /**
     * 获取所有可用工具列表
     */
    public List<ToolInfo> listTools() {
        try {
                String url = getMcpServerUrl() + "/api/tools";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to list tools: HTTP " + response.statusCode());
                }

                ApiResponse<List<ToolDefinitionDTO>> apiResponse = objectMapper.readValue(
                        response.body(),
                        new TypeReference<ApiResponse<List<ToolDefinitionDTO>>>() {}
                );

                if (!apiResponse.isSuccess()) {
                    throw new RuntimeException("Failed to list tools: " + apiResponse.getMessage());
                }

                // 转换为 ToolInfo
                return apiResponse.getData().stream()
                        .map(dto -> {
                            // 从工具名称中提取 provider name (格式: providerName::toolName)
                            String serverName = dto.getProviderId();
                            if (dto.getName() != null && dto.getName().contains("::")) {
                                serverName = dto.getName().split("::")[0];
                            }

                            // 将 inputSchema 对象转为 JSON 字符串
                            String inputSchemaJson = "{}";
                            if (dto.getInputSchema() != null) {
                                try {
                                    inputSchemaJson = objectMapper.writeValueAsString(dto.getInputSchema());
                                } catch (Exception e) {
                                    log.warn("Failed to serialize inputSchema for tool: {}", dto.getName(), e);
                                }
                            }

                            return ToolInfo.builder()
                                    .id(dto.getName())
                                    .name(dto.getName())
                                    .description(dto.getDescription())
                                    .serverId(dto.getProviderId())
                                    .serverName(serverName)
                                    .inputSchema(inputSchemaJson)
                                    .build();
                        })
                        .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to list tools from mcp-server", e);
            throw new RuntimeException("Failed to list tools from mcp-server", e);
        }
    }

    /**
     * 获取所有 Provider 列表
     */
    public List<Map<String, Object>> listProviders() {
        try {
                String url = getMcpServerUrl() + "/api/providers";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to list providers: HTTP " + response.statusCode());
                }

                ApiResponse<List<Map<String, Object>>> apiResponse = objectMapper.readValue(
                        response.body(),
                        new TypeReference<ApiResponse<List<Map<String, Object>>>>() {}
                );

                if (!apiResponse.isSuccess()) {
                    throw new RuntimeException("Failed to list providers: " + apiResponse.getMessage());
                }

                return apiResponse.getData();

        } catch (Exception e) {
            log.error("Failed to list providers from mcp-server", e);
            throw new RuntimeException("Failed to list providers from mcp-server", e);
        }
    }

    /**
     * 注册新的 Provider
     */
    public Map<String, Object> registerProvider(Map<String, Object> config) {
        try {
                String url = getMcpServerUrl() + "/api/providers";
                String jsonBody = objectMapper.writeValueAsString(config);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to register provider: HTTP " + response.statusCode());
                }

                ApiResponse<Map<String, Object>> apiResponse = objectMapper.readValue(
                        response.body(),
                        new TypeReference<ApiResponse<Map<String, Object>>>() {}
                );

                if (!apiResponse.isSuccess()) {
                    throw new RuntimeException("Failed to register provider: " + apiResponse.getMessage());
                }

                return apiResponse.getData();

        } catch (Exception e) {
            log.error("Failed to register provider", e);
            throw new RuntimeException("Failed to register provider", e);
        }
    }

    /**
     * 删除 Provider
     */
    public Map<String, Object> deleteProvider(String providerId) {
        try {
                String url = getMcpServerUrl() + "/api/providers/" + providerId;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .DELETE()
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to delete provider: HTTP " + response.statusCode());
                }

                ApiResponse<Map<String, Object>> apiResponse = objectMapper.readValue(
                        response.body(),
                        new TypeReference<ApiResponse<Map<String, Object>>>() {}
                );

                if (!apiResponse.isSuccess()) {
                    throw new RuntimeException("Failed to delete provider: " + apiResponse.getMessage());
                }

                return apiResponse.getData();

        } catch (Exception e) {
            log.error("Failed to delete provider: {}", providerId, e);
            throw new RuntimeException("Failed to delete provider: " + providerId, e);
        }
    }

    /**
     * 异步调用工具（支持取消）
     */
    public CompletableFuture<ToolCallResultDTO> callToolAsync(String toolId, Map<String, Object> args, String sessionId, Integer stepIndex) {
        try {
            String url = getMcpServerUrl() + "/mcp";

            // 构建 JSON-RPC 请求
            JsonRpcRequest rpcRequest = new JsonRpcRequest();
            rpcRequest.setJsonrpc("2.0");
            rpcRequest.setMethod("tools/call");
            rpcRequest.setId(java.util.UUID.randomUUID().toString());

            Map<String, Object> params = new java.util.HashMap<>();
            params.put("name", toolId);
            params.put("arguments", args != null ? args : new java.util.HashMap<>());
            rpcRequest.setParams(params);

            String jsonBody = objectMapper.writeValueAsString(rpcRequest);

            // 构建 HTTP 请求
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120));

            // 添加 sessionId Header
            if (sessionId != null && !sessionId.isEmpty()) {
                requestBuilder.header("X-Session-Id", sessionId);
                log.debug("Calling tool {} with sessionId: {}", toolId, sessionId);
            }

            // 添加 stepIndex Header
            if (stepIndex != null) {
                requestBuilder.header("X-Step-Index", stepIndex.toString());
                log.debug("Calling tool {} with stepIndex: {}", toolId, stepIndex);
            }

            HttpRequest request = requestBuilder.build();

            // 异步发送请求
            CompletableFuture<HttpResponse<String>> responseFuture = httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(120, TimeUnit.SECONDS);

            // 注册到跟踪器
            if (sessionId != null) {
                executionTracker.registerFuture(sessionId, responseFuture);
                log.debug("[RemoteMcpClient] Registered future for session: {}", sessionId);
            }

            // 处理响应
            return responseFuture
                    .thenApply(response -> {
                        try {
                            if (response.statusCode() != 200) {
                                throw new RuntimeException("Failed to call tool: HTTP " + response.statusCode());
                            }

                            // 解析 JSON-RPC 响应
                            JsonRpcResponse rpcResponse = objectMapper.readValue(
                                    response.body(),
                                    JsonRpcResponse.class
                            );

                            // 检查错误
                            if (rpcResponse.getError() != null) {
                                throw new RuntimeException("Tool call error: " + rpcResponse.getError().getMessage());
                            }

                            // 解析结果为 ToolCallResultDTO
                            if (rpcResponse.getResult() != null) {
                                return objectMapper.convertValue(rpcResponse.getResult(), ToolCallResultDTO.class);
                            }

                            // 如果没有结果，返回空结果
                            ToolCallResultDTO emptyResult = new ToolCallResultDTO();
                            emptyResult.setContent(new java.util.ArrayList<>());
                            emptyResult.setIsError(false);
                            return emptyResult;

                        } catch (Exception e) {
                            log.error("Failed to parse tool response: {}", toolId, e);
                            throw new RuntimeException("Failed to parse tool response: " + toolId, e);
                        }
                    })
                    .whenComplete((result, error) -> {
                        // 清理已完成的任务
                        if (sessionId != null) {
                            executionTracker.cleanup(sessionId);
                            log.debug("[RemoteMcpClient] Cleaned up session: {}", sessionId);
                        }
                        if (error != null) {
                            log.error("Tool call failed: {}", toolId, error);
                        }
                    });

        } catch (Exception e) {
            log.error("Failed to initiate tool call: {}", toolId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public ToolCallResultDTO callTool(String toolId, Map<String, Object> args) {
        return callTool(toolId, args, null, null);
    }



    // 同步版本（向后兼容，内部调用异步版本）
    public ToolCallResultDTO callTool(String toolId, Map<String, Object> args, String sessionId, Integer stepIndex) {
        try {
            return callToolAsync(toolId, args, sessionId, stepIndex).get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Tool call timeout: {}", toolId, e);
            throw new RuntimeException("Tool call timeout: " + toolId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Tool call interrupted: {}", toolId, e);
            throw new RuntimeException("Tool call interrupted: " + toolId, e);
        } catch (ExecutionException e) {
            log.error("Tool call execution failed: {}", toolId, e);
            throw new RuntimeException("Tool call execution failed: " + toolId, e.getCause());
        }
    }


    /**
     * 获取工具回调（用于 AgentFactory）
     */
    public ToolCallback[] getToolCallbacks(List<String> toolIds) {
        // 先获取所有工具定义
        Map<String, ToolInfo> toolInfoMap = new HashMap<>();
        try {
            List<ToolInfo> allTools = listTools();
            for (ToolInfo tool : allTools) {
                toolInfoMap.put(tool.getId(), tool);
            }
        } catch (Exception e) {
            log.error("Failed to fetch tool definitions", e);
        }

        return toolIds.stream()
                .map(toolId -> {
                    ToolInfo toolInfo = toolInfoMap.get(toolId);
                    return (ToolCallback) new RemoteToolCallback(toolId, this, toolInfo);
                })
                .toArray(ToolCallback[]::new);
    }

    // ==================== 内部类 ====================

    /**
     * 远程工具回调包装器
     */
    private static class RemoteToolCallback implements ToolCallback {
        private final String toolId;
        private final RemoteMcpClient client;
        private final ToolInfo toolInfo;

        public RemoteToolCallback(String toolId, RemoteMcpClient client, ToolInfo toolInfo) {
            this.toolId = toolId;
            this.client = client;
            this.toolInfo = toolInfo;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            // 如果没有 inputSchema，提供一个默认的空 schema
            String inputSchema = "{}";
            if (toolInfo != null && toolInfo.getInputSchema() != null) {
                inputSchema = toolInfo.getInputSchema();
            }

            String description = toolInfo != null ? toolInfo.getDescription() : "Remote MCP tool: " + toolId;

            return ToolDefinition.builder()
                    .name(toolId)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            try {
                Map<String, Object> args = client.objectMapper.readValue(
                        toolInput,
                        new TypeReference<Map<String, Object>>() {}
                );
                ToolCallResultDTO result = client.callTool(toolId, args);
                return result.getTextContent();
            } catch (Exception e) {
                throw new RuntimeException("Failed to call remote tool: " + toolId, e);
            }
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            try {
                Map<String, Object> args = client.objectMapper.readValue(
                        toolInput,
                        new TypeReference<Map<String, Object>>() {}
                );

                // 从 ToolContext 获取 sessionId 和 stepIndex
                String sessionId = null;
                Integer stepIndex = null;
                if (toolContext != null && toolContext.getContext() != null) {
                    Object sid = toolContext.getContext().get("sessionId");
                    if (sid != null) {
                        sessionId = sid.toString();
                    }
                    Object si = toolContext.getContext().get("stepIndex");
                    if (si != null) {
                        stepIndex = (Integer) si;
                    }
                }

                ToolCallResultDTO result = client.callTool(toolId, args, sessionId, stepIndex);
                return result.getTextContent();
            } catch (Exception e) {
                throw new RuntimeException("Failed to call remote tool: " + toolId, e);
            }
        }
    }

    // ==================== DTO 类 ====================

    @Data
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
    }

    @Data
    public static class ToolDefinitionDTO {
        private String name;
        private String description;
        private Object inputSchema;
        private String sourceType;
        private String providerId;
    }

    @Data
    public static class ToolCallRequestDTO {
        private String name;
        private Map<String, Object> arguments;
        private String sessionId;
    }

    // ==================== JSON-RPC 相关类 ====================

    @Data
    public static class JsonRpcRequest {
        private String jsonrpc = "2.0";
        private String method;
        private Map<String, Object> params;
        private Object id;
    }

    @Data
    public static class JsonRpcResponse {
        private String jsonrpc;
        private Object id;
        private Object result;
        private JsonRpcError error;
    }

    @Data
    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;
    }

    @Data
    public static class ToolCallResultDTO {
        private List<ContentItem> content;
        private Boolean isError;

        /**
         * 提取文本内容
         */
        public String getTextContent() {
            if (content == null || content.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ContentItem item : content) {
                if ("text".equals(item.getType()) && item.getText() != null) {
                    sb.append(item.getText());
                }
            }
            return sb.toString();
        }

        public boolean isError() {
            return isError != null && isError;
        }
    }

    @Data
    public static class ContentItem {
        private String type;
        private String text;
        private String data;
        private String mimeType;
    }
}
