package com.agent.client;

import com.agent.model.ToolInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Collectors;

/**
 * 远程 MCP 客户端
 * 通过 HTTP 调用 mcp-server 服务
 */
@Slf4j
@Service
public class RemoteMcpClient {

    @Value("${mcp.server.url:http://localhost:8081}")
    private String mcpServerUrl;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RemoteMcpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 获取所有可用工具列表
     */
    public List<ToolInfo> listTools() {
        try {
            String url = mcpServerUrl + "/api/tools";
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
            String url = mcpServerUrl + "/api/providers";
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
            String url = mcpServerUrl + "/api/providers";
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
            String url = mcpServerUrl + "/api/providers/" + providerId;

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
     * 调用远程工具
     */
    public ToolCallResultDTO callTool(String toolId, Map<String, Object> args) {
        try {
            String url = mcpServerUrl + "/api/tools/call";

            ToolCallRequestDTO requestBody = new ToolCallRequestDTO();
            requestBody.setName(toolId);
            requestBody.setArguments(args);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to call tool: HTTP " + response.statusCode());
            }

            ApiResponse<ToolCallResultDTO> apiResponse = objectMapper.readValue(
                    response.body(),
                    new TypeReference<ApiResponse<ToolCallResultDTO>>() {}
            );

            if (!apiResponse.isSuccess()) {
                throw new RuntimeException("Failed to call tool: " + apiResponse.getMessage());
            }

            return apiResponse.getData();

        } catch (Exception e) {
            log.error("Failed to call tool: {}", toolId, e);
            throw new RuntimeException("Failed to call tool: " + toolId, e);
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
        public String getName() {
            return toolId;
        }

        @Override
        public String getDescription() {
            return toolInfo != null ? toolInfo.getDescription() : "Remote MCP tool: " + toolId;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            // 如果没有 inputSchema，提供一个默认的空 schema
            String inputSchema = "{}";
            if (toolInfo != null && toolInfo.getInputSchema() != null) {
                inputSchema = toolInfo.getInputSchema();
            }
            
            return ToolDefinition.builder()
                    .name(toolId)
                    .description(getDescription())
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
            return call(toolInput);
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
