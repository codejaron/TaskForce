package com.agent.mcpserver.service;

import com.agent.mcpserver.config.McpClientPoolProperties;
import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolVO;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具路由器
 * 根据 tool_name 路由到对应的 MCP Client
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRouter {
    private static final ObjectMapper LOG_OBJECT_MAPPER = new ObjectMapper();

    private final ToolProviderConfigService configService;
    private final McpClientPoolProperties clientPoolProperties;

    @Lazy
    private final NativeToolScanner nativeToolScanner;

    /**
     * MCP Client 池缓存：providerId -> ProviderClientPool
     */
    private final Map<String, ProviderClientPool> clientPoolCache = new ConcurrentHashMap<>();

    /**
     * Provider 名称映射：providerId -> providerName
     */
    private final Map<String, String> clientNameMap = new ConcurrentHashMap<>();

    /**
     * Provider 类型映射：providerId -> providerType
     */
    private final Map<String, ToolProviderConfig.ProviderType> providerTypeMap = new ConcurrentHashMap<>();

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
        clientPoolCache.values().forEach(ProviderClientPool::closeAll);
        clientPoolCache.clear();
        clientNameMap.clear();
        providerTypeMap.clear();
        toolsCache.clear();
        toolRouteTable.clear();
    }

    /**
     * 重新加载所有 Provider
     */
    public synchronized void reloadProviders() {
        log.info("[ToolRouter] Reloading all providers...");

        // 关闭现有 Client
        clientPoolCache.values().forEach(ProviderClientPool::closeAll);
        clientPoolCache.clear();
        clientNameMap.clear();
        providerTypeMap.clear();
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
                clientPoolCache.size(),
                toolRouteTable.size(),
                nativeToolScanner.listTools().size());
    }

    /**
     * 注册单个 Provider
     * 工具 ID 格式：{providerName}::{toolName}
     */
    public void registerProvider(ToolProviderConfig config) throws Exception {
        String providerId = config.getId();
        String providerName = config.getName();

        // 如果已存在，先关闭
        ProviderClientPool existing = clientPoolCache.remove(providerId);
        if (existing != null) {
            log.info("[ToolRouter] Overwriting existing provider: {}", providerName);
            existing.closeAll();
            clientNameMap.remove(providerId);
            providerTypeMap.remove(providerId);
            toolsCache.remove(providerId);
            toolRouteTable.entrySet().removeIf(entry -> entry.getValue().equals(providerId));
        }

        int poolSize = resolvePoolSize(config);
        ProviderClientPool clientPool = createClientPool(config, poolSize);

        // 获取工具列表并缓存
        McpSchema.ListToolsResult toolsResult = clientPool.referenceClient().listTools();
        List<McpSchema.Tool> tools = toolsResult.tools();

        // 注册到缓存
        clientPoolCache.put(providerId, clientPool);
        clientNameMap.put(providerId, providerName);
        providerTypeMap.put(providerId, config.getType());

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

        log.info("[ToolRouter] Registered provider: {} ({}) with {} tools, pool size {} (prefix: {}::)",
                config.getName(), providerId, tools.size(), poolSize, providerName);
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
     * 创建并初始化 Provider 客户端池
     */
    private ProviderClientPool createClientPool(ToolProviderConfig config, int poolSize) throws Exception {
        List<McpSyncClient> clients = new ArrayList<>(poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                McpSyncClient client = createClient(config);
                client.initialize();
                clients.add(client);
            }
            return new ProviderClientPool(config.getId(), config.getName(), clients);
        } catch (Exception e) {
            clients.forEach(this::closeClientQuietly);
            throw e;
        }
    }

    /**
     * 解析 Provider 池大小（按 Provider 类型取默认值）
     */
    private int resolvePoolSize(ToolProviderConfig config) {
        int poolSize = switch (config.getType()) {
            case STDIO -> clientPoolProperties.getStdioDefaultSize();
            case REMOTE_SSE -> clientPoolProperties.getRemoteSseDefaultSize();
            case STREAMABLE_HTTP -> clientPoolProperties.getStreamableHttpDefaultSize();
        };

        if (poolSize <= 0) {
            log.warn("[ToolRouter] Invalid pool size {} for provider {}, fallback to 1",
                    poolSize, config.getName());
            poolSize = 1;
        }

        int maxPoolSize = Math.max(clientPoolProperties.getMaxPoolSize(), 1);
        if (poolSize > maxPoolSize) {
            log.warn("[ToolRouter] Pool size {} for provider {} exceeds max {}, clamped",
                    poolSize, config.getName(), maxPoolSize);
            poolSize = maxPoolSize;
        }
        return poolSize;
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
        ProviderClientPool clientPool = clientPoolCache.remove(providerId);
        if (clientPool != null) {
            clientPool.closeAll();
            clientNameMap.remove(providerId);
            providerTypeMap.remove(providerId);
            toolsCache.remove(providerId);
            toolRouteTable.entrySet().removeIf(entry -> entry.getValue().equals(providerId));
            log.info("[ToolRouter] Unregistered provider: {}", providerId);
        }
    }

    /**
     * 调用工具
     */
    public ToolCallResult callTool(String globalToolId, Map<String, Object> arguments, String sessionId) {
        Map<String, Object> safeArguments = arguments != null ? arguments : Map.of();
        String argsPreview = toCompactJson(safeArguments);
        long startedAtNanos = System.nanoTime();

        log.info("[ToolRouter] Tool call start: tool={}, sessionId={}, args={}",
                globalToolId, sessionId, argsPreview);

        // 1. 先检查 Native 工具
        if (nativeToolScanner.hasTool(globalToolId)) {
            try {
                if (sessionId != null) {
                    com.agent.mcpserver.context.SessionContext.setSessionId(sessionId);
                }
                Object stepIndexObj = safeArguments.get("stepIndex");
                if (stepIndexObj instanceof Integer) {
                    com.agent.mcpserver.context.SessionContext.setStepIndex((Integer) stepIndexObj);
                }
                ToolCallResult nativeResult = nativeToolScanner.callTool(globalToolId, safeArguments);
                logToolCallFinish(globalToolId, "native", sessionId, startedAtNanos, nativeResult, null);
                return nativeResult;
            } finally {
                com.agent.mcpserver.context.SessionContext.clear();
            }
        }

        // 2. 查找 Provider
        String providerId = toolRouteTable.get(globalToolId);
        if (providerId == null) {
            log.warn("[ToolRouter] Tool not found: {}", globalToolId);
            ToolCallResult errorResult = ToolCallResult.error("Tool not found: " + globalToolId);
            logToolCallFinish(globalToolId, "provider:missing", sessionId, startedAtNanos, errorResult, null);
            return errorResult;
        }

        ProviderClientPool clientPool = clientPoolCache.get(providerId);
        if (clientPool == null) {
            log.warn("[ToolRouter] Provider not available: {}", providerId);
            ToolCallResult errorResult = ToolCallResult.error("Provider not available: " + providerId);
            logToolCallFinish(globalToolId, "provider:" + providerId, sessionId, startedAtNanos, errorResult, null);
            return errorResult;
        }

        // 提取原始工具名称（去掉前缀）
        String originalToolName = globalToolId;
        if (globalToolId.contains("::")) {
            originalToolName = globalToolId.substring(globalToolId.indexOf("::") + 2);
        }

        McpSyncClient borrowedClient = null;
        try {
            borrowedClient = clientPool.borrowClient(clientPoolProperties.getAcquireTimeoutSeconds());
            if (borrowedClient == null) {
                log.warn("[ToolRouter] Provider pool exhausted: providerId={}, poolSize={}",
                        providerId, clientPool.size());
                ToolCallResult errorResult = ToolCallResult.error("Provider is busy, please retry: " + providerId);
                logToolCallFinish(globalToolId, "provider:" + providerId, sessionId, startedAtNanos, errorResult, null);
                return errorResult;
            }

            // 调用 MCP Client
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(originalToolName, safeArguments);
            McpSchema.CallToolResult result = borrowedClient.callTool(request);

            // 转换为 ToolCallResult
            ToolCallResult convertedResult = convertToToolCallResult(result);
            logToolCallFinish(globalToolId, "provider:" + providerId, sessionId, startedAtNanos, convertedResult, null);
            return convertedResult;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ToolRouter] Interrupted while borrowing client: {}", globalToolId, e);
            ToolCallResult errorResult = ToolCallResult.error("Tool call interrupted");
            logToolCallFinish(globalToolId, "provider:" + providerId, sessionId, startedAtNanos, errorResult, e);
            return errorResult;
        } catch (Exception e) {
            log.error("[ToolRouter] Tool call failed: {}", globalToolId, e);
            ToolCallResult errorResult = ToolCallResult.error("Tool call failed: " + e.getMessage());
            logToolCallFinish(globalToolId, "provider:" + providerId, sessionId, startedAtNanos, errorResult, e);
            return errorResult;
        } finally {
            clientPool.returnClient(borrowedClient);
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
            ToolProviderConfig.ProviderType providerType = providerTypeMap.get(providerId);

            try {
                String sourceType = providerType != null ? providerType.name() : "UNKNOWN";

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
        ToolProviderConfig.ProviderType providerType = providerTypeMap.get(providerId);
        List<ToolVO> tools = new ArrayList<>();

        try {
            String sourceType = providerType != null ? providerType.name() : "UNKNOWN";

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
            ToolProviderConfig.ProviderType providerType = providerTypeMap.get(providerId);

            try {
                providers.add(new ProviderInfo(
                        providerId,
                        providerName,
                        providerType,
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
        return nativeToolScanner.hasTool(toolName)
                || toolRouteTable.containsKey(toolName);
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

        // 2. 检查其他 Provider
        String providerId = toolRouteTable.get(toolName);
        if (providerId == null) {
            return Optional.empty();
        }

        return listToolsByProvider(providerId).stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst();
    }

    private void closeClientQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (Exception e) {
            log.warn("[ToolRouter] Error closing MCP client", e);
        }
    }

    private void logToolCallFinish(String toolId,
                                   String route,
                                   String sessionId,
                                   long startedAtNanos,
                                   ToolCallResult result,
                                   Exception exception) {
        long durationMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        String resultPreview = summarizeResult(result);

        if (exception == null) {
            log.info("[ToolRouter] Tool call done: tool={}, route={}, sessionId={}, durationMs={}, result={}",
                    toolId, route, sessionId, durationMs, resultPreview);
            return;
        }

        log.info("[ToolRouter] Tool call done with exception: tool={}, route={}, sessionId={}, durationMs={}, exception={}, result={}",
                toolId, route, sessionId, durationMs, exception.getClass().getSimpleName(), resultPreview);
    }

    private String summarizeResult(ToolCallResult result) {
        if (result == null) {
            return "null";
        }
        int contentCount = result.getContent() == null ? 0 : result.getContent().size();
        String first = "";
        if (result.getContent() != null && !result.getContent().isEmpty()) {
            ToolCallResult.Content firstContent = result.getContent().get(0);
            if (firstContent != null) {
                if (firstContent.getText() != null && !firstContent.getText().isBlank()) {
                    first = truncate(firstContent.getText().replaceAll("\\s+", " "), 512);
                } else if (firstContent.getData() != null && !firstContent.getData().isBlank()) {
                    first = truncate(firstContent.getData(), 120);
                }
            }
        }
        return String.format("{isError=%s, contentCount=%d, first=%s}",
                Boolean.TRUE.equals(result.getIsError()), contentCount, first);
    }

    private String toCompactJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return truncate(LOG_OBJECT_MAPPER.writeValueAsString(value), 2048);
        } catch (Exception e) {
            return truncate(String.valueOf(value), 2048);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...(truncated)";
    }

    /**
     * 单个 Provider 对应的客户端池
     */
    private final class ProviderClientPool {
        private final String providerId;
        private final String providerName;
        private final List<McpSyncClient> allClients;
        private final BlockingQueue<McpSyncClient> availableClients;

        private ProviderClientPool(String providerId, String providerName, List<McpSyncClient> clients) {
            this.providerId = providerId;
            this.providerName = providerName;
            this.allClients = List.copyOf(clients);
            this.availableClients = new ArrayBlockingQueue<>(clients.size(), true);
            this.availableClients.addAll(clients);
        }

        private McpSyncClient referenceClient() {
            return allClients.get(0);
        }

        private int size() {
            return allClients.size();
        }

        private McpSyncClient borrowClient(int timeoutSeconds) throws InterruptedException {
            int safeTimeoutSeconds = Math.max(timeoutSeconds, 1);
            return availableClients.poll(safeTimeoutSeconds, TimeUnit.SECONDS);
        }

        private void returnClient(McpSyncClient client) {
            if (client == null) {
                return;
            }
            boolean returned = availableClients.offer(client);
            if (!returned) {
                log.warn("[ToolRouter] Client pool overflow while returning client: {} ({})",
                        providerName, providerId);
                closeClientQuietly(client);
            }
        }

        private void closeAll() {
            allClients.forEach(ToolRouter.this::closeClientQuietly);
            availableClients.clear();
            log.info("[ToolRouter] Closed provider pool: {} ({}) size={}",
                    providerName, providerId, allClients.size());
        }
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
