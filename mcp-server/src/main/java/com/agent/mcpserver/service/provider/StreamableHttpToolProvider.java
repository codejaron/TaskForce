package com.agent.mcpserver.service.provider;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.protocol.JsonRpcRequest;
import com.agent.mcpserver.protocol.JsonRpcResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Streamable HTTP 工具提供者
 * 通过 HTTP POST 发送 JSON-RPC 请求到远程服务
 * 与 REMOTE_SSE 不同，不需要建立 SSE 连接，直接使用固定的 HTTP URL
 */
@Slf4j
public class StreamableHttpToolProvider extends AbstractToolProvider {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private WebClient webClient;
    private String httpUrl;
    private Map<String, String> headers = new HashMap<>();
    private int timeoutSeconds = 30;

    @Override
    protected void doInitialize(ToolProviderConfig config) throws Exception {
        this.httpUrl = config.getHttpUrl();
        this.timeoutSeconds = config.getTimeout() != null ? config.getTimeout() : 30;

        if (httpUrl == null || httpUrl.isBlank()) {
            throw new IllegalArgumentException("httpUrl is required for STREAMABLE_HTTP provider");
        }

        // 解析请求头
        if (config.getHeaders() != null && !config.getHeaders().isBlank()) {
            this.headers = objectMapper.readValue(config.getHeaders(), new TypeReference<Map<String, String>>() {});
        }

        // 构建 WebClient
        WebClient.Builder builder = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        // 添加自定义请求头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.defaultHeader(entry.getKey(), entry.getValue());
        }

        this.webClient = builder.build();

        log.info("[STREAMABLE_HTTP] Initialized with URL: {}", httpUrl);

        // 获取工具列表
        fetchTools();
    }

    /**
     * 获取远程工具列表
     */
    private void fetchTools() throws Exception {
        log.info("[STREAMABLE_HTTP] Fetching tools from remote server...");

        JsonRpcRequest request = JsonRpcRequest.builder()
                .method("tools/list")
                .id(UUID.randomUUID().toString())
                .params(Map.of())
                .build();

        JsonRpcResponse response = sendRequest(request);

        if (response.getError() != null) {
            throw new Exception("Failed to list tools: " + response.getError().getMessage());
        }

        // 解析工具列表
        if (response.getResult() != null) {
            JsonNode resultNode = objectMapper.valueToTree(response.getResult());
            if (resultNode.has("tools")) {
                for (JsonNode toolNode : resultNode.get("tools")) {
                    ToolDefinition tool = ToolDefinition.builder()
                            .name(toolNode.get("name").asText())
                            .description(toolNode.has("description") ? toolNode.get("description").asText() : "")
                            .inputSchema(toolNode.has("inputSchema") ? 
                                    objectMapper.treeToValue(toolNode.get("inputSchema"), Object.class) : null)
                            .build();
                    registerTool(tool);
                }
            }
        }

        log.info("[STREAMABLE_HTTP] Fetched {} tools", toolCache.size());
    }

    /**
     * 发送 JSON-RPC 请求到远程服务
     */
    private JsonRpcResponse sendRequest(JsonRpcRequest request) throws Exception {
        String requestBody = objectMapper.writeValueAsString(request);
        
        log.debug("[STREAMABLE_HTTP] Sending request to {}: {}", httpUrl, requestBody);

        String responseBody = webClient.post()
                .uri(httpUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        log.debug("[STREAMABLE_HTTP] Received response: {}", responseBody);

        return objectMapper.readValue(responseBody, JsonRpcResponse.class);
    }

    @Override
    protected void doShutdown() {
        // WebClient 不需要显式关闭
        this.webClient = null;
        this.httpUrl = null;
    }

    @Override
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments, String sessionId) {
        if (!connected || httpUrl == null) {
            return ToolCallResult.error("Provider not connected");
        }

        if (!hasTool(toolName)) {
            return ToolCallResult.error("Tool not found: " + toolName);
        }

        try {
            // 构建调用请求
            JsonRpcRequest request = JsonRpcRequest.builder()
                    .method("tools/call")
                    .id(UUID.randomUUID().toString())
                    .params(Map.of(
                            "name", toolName,
                            "arguments", arguments != null ? arguments : Map.of()
                    ))
                    .build();

            // 发送请求
            JsonRpcResponse response = sendRequest(request);

            // 处理响应
            if (response.getError() != null) {
                return ToolCallResult.error(response.getError().getMessage());
            }

            // 解析结果
            if (response.getResult() != null) {
                JsonNode resultNode = objectMapper.valueToTree(response.getResult());
                
                // 检查是否错误
                boolean isError = resultNode.has("isError") && resultNode.get("isError").asBoolean();
                
                // 提取文本内容
                StringBuilder content = new StringBuilder();
                if (resultNode.has("content")) {
                    for (JsonNode contentNode : resultNode.get("content")) {
                        if (contentNode.has("text")) {
                            content.append(contentNode.get("text").asText());
                        }
                    }
                }

                if (isError) {
                    return ToolCallResult.error(content.toString());
                }
                return ToolCallResult.text(content.toString());
            }

            return ToolCallResult.text("");

        } catch (Exception e) {
            log.error("[STREAMABLE_HTTP] Tool call failed: {} - {}", toolName, e.getMessage(), e);
            return ToolCallResult.error("Tool call failed: " + e.getMessage());
        }
    }
}
