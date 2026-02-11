package com.agent.mcpserver.service;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolVO;
import com.agent.mcpserver.entity.ToolProviderConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具路由器
 * 根据 tool_name 路由到对应的 MCP Client
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRouter {

    private final ToolProviderConfigService configService;

    @Lazy
    private final NativeToolScanner nativeToolScanner;

    /**
     * MCP Client 缓存：providerId -> McpSyncClient
     */
    private final Map<String, McpSyncClient> clientCache = new ConcurrentHashMap<>();

    /**
     * Provider 名称映射：providerId -> providerName
     */
    private final Map<String, String> clientNameMap = new ConcurrentHashMap<>();

    /**
     * 工具缓存：providerId -> List<McpSchema.Tool>
     * 缓存每个 provider 的工具列表，避免并发调用 client.listTools() 导致的消息队列冲突
     */
    private final Map<String, List<McpSchema.Tool>> toolsCache = new ConcurrentHashMap<>();

    /**
     * 工具路由表：globalToolId -> providerId
     */
    private final Map<String, String> toolRouteTable = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("[ToolRouter] Initializing tool router...");
        reloadProviders();
    }

    @PreDestroy
    public void cleanup() {
        log.info("[ToolRouter] Cleaning up providers...");
        clientCache.values().forEach(client -> {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("[ToolRouter] Error closing client", e);
            }
        });
        clientCache.clear();
        clientNameMap.clear();
        toolRouteTable.clear();
    }

    /**
     * 重新加载所有 Provider
     */
    public synchronized void reloadProviders() {
        log.info("[ToolRouter] Reloading all providers...");

        // 关闭现有 Client
        clientCache.values().forEach(client -> {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("[ToolRouter] Error closing client during reload", e);
            }
        });
        clientCache.clear();
        clientNameMap.clear();
        toolsCache.clear();
        toolRouteTable.clear();

        // 加载配置中的 Provider
        List<ToolProviderConfig> configs = configService.listEnabledConfigs();
        for (ToolProviderConfig config : configs) {
            try {
                registerProvider(config);
            } catch (Exception e) {
                log.error("[ToolRouter] Failed to register provider: {}", config.getName(), e);
            }
        }

        log.info("[ToolRouter] Loaded {} providers with {} tools (+ {} native tools)",
                clientCache.size(), toolRouteTable.size(), nativeToolScanner.listTools().size());
    }

    /**
     * 注册单个 Provider
     * 工具 ID 格式：{providerName}::{toolName}
     */
    public void registerProvider(ToolProviderConfig config) throws Exception {
        String providerId = config.getId();
        String providerName = config.getName();

        // 如果已存在，先关闭
        McpSyncClient existing = clientCache.remove(providerId);
        if (existing != null) {
            log.info("[ToolRouter] Overwriting existing provider: {}", providerName);
            existing.closeGracefully();
            clientNameMap.remove(providerId);
            toolsCache.remove(providerId);
            toolRouteTable.entrySet().removeIf(entry -> entry.getValue().equals(providerId));
        }

        // 创建 Transport
        McpSyncClient client = createClient(config);

        // 初始化连接
        client.initialize();

        // 注册到缓存
        clientCache.put(providerId, client);
        clientNameMap.put(providerId, providerName);

        // 获取工具列表并缓存
        McpSchema.ListToolsResult toolsResult = client.listTools();
        List<McpSchema.Tool> tools = toolsResult.tools();

        // 缓存工具列表（避免并发调用 client.listTools()）
        toolsCache.put(providerId, tools);

        // 更新路由表
        for (McpSchema.Tool tool : tools) {
            String originalToolName = tool.name();
            String globalToolId = providerName + "::" + originalToolName;

            // 注册路由
            if (toolRouteTable.containsKey(globalToolId)) {
                log.warn("[ToolRouter] Tool ID conflict: {} (will be overwritten)", globalToolId);
            }
            toolRouteTable.put(globalToolId, providerId);
        }

        // 更新配置状态
        configService.updateConnectionStatus(providerId, true, tools.size(), null);

        log.info("[ToolRouter] Registered provider: {} ({}) with {} tools (prefix: {}::)",
                config.getName(), providerId, tools.size(), providerName);
    }

    /**
     * 创建 MCP Client（支持 STDIO、REMOTE_SSE、STREAMABLE_HTTP）
     */
    private McpSyncClient createClient(ToolProviderConfig config) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);

        return switch (config.getType()) {
            case STDIO -> {
                String command = config.getCommand();
                List<String> args = parseArgs(config.getArgs());
                Map<String, String> env = parseEnv(config.getEnv());

                ServerParameters.Builder builder = ServerParameters.builder(command);
                if (!args.isEmpty()) {
                    builder.args(args);
                }
                if (!env.isEmpty()) {
                    builder.env(env);
                }
                ServerParameters serverParams = builder.build();

                StdioClientTransport transport = new StdioClientTransport(serverParams, jsonMapper);

                yield McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 30))
                        .build();
            }
            case REMOTE_SSE -> {
                String url = config.getSseUrl();

                HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(url)
                        .build();

                yield McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 30))
                        .build();
            }
            case STREAMABLE_HTTP -> {
                String url = config.getHttpUrl();

                HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(url)
                        .build();

                yield McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 30))
                        .build();
            }
        };
    }

    /**
     * 解析 JSON 数组字符串为 List
     */
    private List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(argsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[ToolRouter] Failed to parse args: {}", argsJson, e);
            return List.of();
        }
    }

    /**
     * 解析 JSON 对象字符串为 Map
     */
    private Map<String, String> parseEnv(String envJson) {
        if (envJson == null || envJson.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(envJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("[ToolRouter] Failed to parse env: {}", envJson, e);
            return Map.of();
        }
    }

    /**
     * 注销 Provider
     */
    public void unregisterProvider(String providerId) {
        McpSyncClient client = clientCache.remove(providerId);
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("[ToolRouter] Error closing client", e);
            }
            clientNameMap.remove(providerId);
            toolsCache.remove(providerId);
            toolRouteTable.entrySet().removeIf(entry -> entry.getValue().equals(providerId));
            log.info("[ToolRouter] Unregistered provider: {}", providerId);
        }
    }

    /**
     * 调用工具
     */
    public ToolCallResult callTool(String globalToolId, Map<String, Object> arguments, String sessionId) {
        // 1. 先检查 Native 工具
        if (nativeToolScanner.hasTool(globalToolId)) {
            log.info("[ToolRouter] Routing to native tool: {}", globalToolId);
            try {
                if (sessionId != null) {
                    com.agent.mcpserver.context.SessionContext.setSessionId(sessionId);
                }
                Object stepIndexObj = arguments.get("stepIndex");
                if (stepIndexObj instanceof Integer) {
                    com.agent.mcpserver.context.SessionContext.setStepIndex((Integer) stepIndexObj);
                }
                return nativeToolScanner.callTool(globalToolId, arguments);
            } finally {
                com.agent.mcpserver.context.SessionContext.clear();
            }
        }

        // 2. 查找 Provider
        String providerId = toolRouteTable.get(globalToolId);
        if (providerId == null) {
            log.warn("[ToolRouter] Tool not found: {}", globalToolId);
            return ToolCallResult.error("Tool not found: " + globalToolId);
        }

        McpSyncClient client = clientCache.get(providerId);
        if (client == null) {
            log.warn("[ToolRouter] Provider not available: {}", providerId);
            return ToolCallResult.error("Provider not available: " + providerId);
        }

        // 提取原始工具名称（去掉前缀）
        String originalToolName = globalToolId;
        if (globalToolId.contains("::")) {
            originalToolName = globalToolId.substring(globalToolId.indexOf("::") + 2);
        }

        log.info("[ToolRouter] Routing tool call: {} -> {} (original: {})",
                globalToolId, providerId, originalToolName);

        try {
            // 调用 MCP Client
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(originalToolName, arguments);
            McpSchema.CallToolResult result = client.callTool(request);

            // 转换为 ToolCallResult
            return convertToToolCallResult(result);
        } catch (Exception e) {
            log.error("[ToolRouter] Tool call failed: {}", globalToolId, e);
            return ToolCallResult.error("Tool call failed: " + e.getMessage());
        }
    }

    /**
     * 转换 McpSchema.CallToolResult 为 ToolCallResult
     */
    private ToolCallResult convertToToolCallResult(McpSchema.CallToolResult mcpResult) {
        List<ToolCallResult.Content> contents = new ArrayList<>();

        for (Object contentObj : mcpResult.content()) {
            if (contentObj instanceof McpSchema.TextContent textContent) {
                contents.add(ToolCallResult.Content.builder()
                        .type("text")
                        .text(textContent.text())
                        .build());
            } else if (contentObj instanceof McpSchema.ImageContent imageContent) {
                contents.add(ToolCallResult.Content.builder()
                        .type("image")
                        .data(imageContent.data())
                        .mimeType(imageContent.mimeType())
                        .build());
            } else if (contentObj instanceof McpSchema.EmbeddedResource resource) {
                // 处理 EmbeddedResource
                if (resource.resource() instanceof McpSchema.TextResourceContents textResource) {
                    contents.add(ToolCallResult.Content.builder()
                            .type("text")
                            .text(textResource.text())
                            .build());
                } else if (resource.resource() instanceof McpSchema.BlobResourceContents blobResource) {
                    contents.add(ToolCallResult.Content.builder()
                            .type("image")
                            .data(blobResource.blob())
                            .mimeType(blobResource.mimeType())
                            .build());
                }
            }
        }

        return ToolCallResult.builder()
                .content(contents)
                .isError(mcpResult.isError() != null && mcpResult.isError())
                .build();
    }

    /**
     * 列出所有可用工具（包括 Native 工具）
     */
    public List<ToolVO> listAllTools() {
        List<ToolVO> all = new ArrayList<>();

        // 1. Native 工具
        all.addAll(nativeToolScanner.listTools());

        // 2. MCP Provider 工具（使用缓存，避免并发调用 client.listTools()）
        for (Map.Entry<String, List<McpSchema.Tool>> entry : toolsCache.entrySet()) {
            String providerId = entry.getKey();
            List<McpSchema.Tool> cachedTools = entry.getValue();
            String providerName = clientNameMap.get(providerId);

            try {
                ToolProviderConfig config = configService.getById(providerId);
                String sourceType = config != null ? config.getType().name() : "UNKNOWN";

                for (McpSchema.Tool tool : cachedTools) {
                    String globalToolId = providerName + "::" + tool.name();

                    all.add(ToolVO.builder()
                            .name(globalToolId)
                            .description(tool.description())
                            .inputSchema(tool.inputSchema())
                            .sourceType(sourceType)
                            .providerId(providerId)
                            .build());
                }
            } catch (Exception e) {
                log.error("[ToolRouter] Failed to list tools for provider: {}", providerId, e);
            }
        }

        return all;
    }

    /**
     * 列出指定 Provider 的工具（使用缓存）
     */
    public List<ToolVO> listToolsByProvider(String providerId) {
        // 检查是否是 Native
        if ("native".equals(providerId)) {
            return nativeToolScanner.listTools();
        }

        List<McpSchema.Tool> cachedTools = toolsCache.get(providerId);
        if (cachedTools == null) {
            return List.of();
        }

        String providerName = clientNameMap.get(providerId);
        List<ToolVO> tools = new ArrayList<>();

        try {
            ToolProviderConfig config = configService.getById(providerId);
            String sourceType = config != null ? config.getType().name() : "UNKNOWN";

            for (McpSchema.Tool tool : cachedTools) {
                String globalToolId = providerName + "::" + tool.name();

                tools.add(ToolVO.builder()
                        .name(globalToolId)
                        .description(tool.description())
                        .inputSchema(tool.inputSchema())
                        .sourceType(sourceType)
                        .providerId(providerId)
                        .build());
            }
        } catch (Exception e) {
            log.error("[ToolRouter] Failed to list tools for provider: {}", providerId, e);
        }

        return tools;
    }

    /**
     * 获取 Provider 列表
     */
    public List<ProviderInfo> listProviders() {
        List<ProviderInfo> providers = new ArrayList<>();

        // 1. Native Provider
        int nativeToolCount = nativeToolScanner.listTools().size();
        if (nativeToolCount > 0) {
            providers.add(new ProviderInfo(
                    "native",
                    "native",
                    null,
                    true,
                    nativeToolCount
            ));
        }

        // 2. 其他 Providers（使用缓存）
        for (Map.Entry<String, List<McpSchema.Tool>> entry : toolsCache.entrySet()) {
            String providerId = entry.getKey();
            String providerName = clientNameMap.get(providerId);
            List<McpSchema.Tool> cachedTools = entry.getValue();

            try {
                ToolProviderConfig config = configService.getById(providerId);

                providers.add(new ProviderInfo(
                        providerId,
                        providerName,
                        config != null ? config.getType() : null,
                        true,
                        cachedTools.size()
                ));
            } catch (Exception e) {
                log.error("[ToolRouter] Failed to get provider info: {}", providerId, e);
            }
        }

        return providers;
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String toolName) {
        return nativeToolScanner.hasTool(toolName) || toolRouteTable.containsKey(toolName);
    }

    /**
     * 获取工具定义
     */
    public Optional<ToolVO> getTool(String toolName) {
        // 1. 检查 Native
        if (nativeToolScanner.hasTool(toolName)) {
            return nativeToolScanner.listTools().stream()
                    .filter(t -> t.getName().equals(toolName))
                    .findFirst();
        }

        // 2. 检查 Provider
        String providerId = toolRouteTable.get(toolName);
        if (providerId == null) {
            return Optional.empty();
        }

        return listToolsByProvider(providerId).stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst();
    }

    /**
     * Provider 信息 DTO
     */
    public record ProviderInfo(
            String id,
            String name,
            ToolProviderConfig.ProviderType type,
            boolean connected,
            int toolCount
    ) {}
}
