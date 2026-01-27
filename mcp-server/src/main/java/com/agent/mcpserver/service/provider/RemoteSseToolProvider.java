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
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 远程 SSE 工具提供者
 * 转发工具调用到远程 MCP SSE 服务
 */
@Slf4j
public class RemoteSseToolProvider extends AbstractToolProvider {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private WebClient webClient;
    private String sseUrl;
    private String messageUrl;
    private Map<String, String> headers = new HashMap<>();
    private int timeoutSeconds = 30;

    @Override
    protected void doInitialize(ToolProviderConfig config) throws Exception {
        this.sseUrl = config.getSseUrl();
        this.timeoutSeconds = config.getTimeout() != null ? config.getTimeout() : 30;

        // 解析请求头
        if (config.getHeaders() != null && !config.getHeaders().isBlank()) {
            this.headers = objectMapper.readValue(config.getHeaders(), new TypeReference<Map<String, String>>() {});
        }

        // 构建 WebClient
        WebClient.Builder builder = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);

        // 添加自定义请求头
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.defaultHeader(entry.getKey(), entry.getValue());
        }

        this.webClient = builder.build();

        // 连接到远程 SSE 服务获取 message URL
        connectAndGetMessageUrl();

        // 获取工具列表
        fetchTools();
    }

    /**
     * 连接到 SSE 服务获取 message URL
     */
    private void connectAndGetMessageUrl() throws Exception {
        log.info("[REMOTE_SSE] Connecting to SSE endpoint: {}", sseUrl);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> msgUrlRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        Flux<String> sseFlux = webClient.get()
                .uri(sseUrl)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds));

        sseFlux.subscribe(
                event -> {
                    try {
                        // 解析 SSE 事件获取 endpoint
                        if (event.contains("endpoint")) {
                            JsonNode node = objectMapper.readTree(event);
                            if (node.has("endpoint")) {
                                String endpoint = node.get("endpoint").asText();
                                // 构建完整的 message URL
                                URI baseUri = new URI(sseUrl);
                                msgUrlRef.set(baseUri.resolve(endpoint).toString());
                                latch.countDown();
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[REMOTE_SSE] Failed to parse SSE event: {}", event);
                    }
                },
                error -> {
                    errorRef.set(new Exception("SSE connection failed: " + error.getMessage()));
                    latch.countDown();
                },
                () -> {
                    if (msgUrlRef.get() == null) {
                        errorRef.set(new Exception("No endpoint received from SSE"));
                    }
                    latch.countDown();
                }
        );

        // 等待连接完成
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new Exception("SSE connection timeout");
        }

        if (errorRef.get() != null) {
            throw errorRef.get();
        }

        this.messageUrl = msgUrlRef.get();
        if (this.messageUrl == null) {
            // 如果没有获取到 endpoint，使用默认的 message 路径
            URI baseUri = new URI(sseUrl);
            this.messageUrl = baseUri.resolve("/message").toString();
        }

        log.info("[REMOTE_SSE] Message URL: {}", messageUrl);
    }

    /**
     * 获取远程工具列表
     */
    private void fetchTools() throws Exception {
        log.info("[REMOTE_SSE] Fetching tools from remote server...");

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
    }

    /**
     * 发送 JSON-RPC 请求
     */
    private JsonRpcResponse sendRequest(JsonRpcRequest request) throws Exception {
        String responseBody = webClient.post()
                .uri(messageUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(request))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return objectMapper.readValue(responseBody, JsonRpcResponse.class);
    }

    @Override
    protected void doShutdown() {
        // WebClient 不需要显式关闭
        this.webClient = null;
        this.messageUrl = null;
    }

    @Override
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments, String sessionId) {
        if (!connected || messageUrl == null) {
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
            log.error("[REMOTE_SSE] Tool call failed: {} - {}", toolName, e.getMessage(), e);
            return ToolCallResult.error("Tool call failed: " + e.getMessage());
        }
    }
}
