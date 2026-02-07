package com.agent.mcpserver.service;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.service.provider.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具路由器
 * 根据 tool_name 路由到对应的 Provider
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRouter {


    private final ToolProviderConfigService configService;
    @Lazy
    private final NativeToolScanner nativeToolScanner;

    /**
     * Provider 缓存：providerId -> ToolProvider
     */
    private final Map<String, ToolProvider> providerCache = new ConcurrentHashMap<>();

    /**
     * 工具路由表：toolName -> providerId
     */
    private final Map<String, String> toolRouteTable = new ConcurrentHashMap<>();

    // 修改点：使用 @EventListener(ApplicationReadyEvent.class) 替代 @PostConstruct
    // 这会将扫描推迟到所有 Bean（包括 McpProtocolHandler）都完全初始化之后。
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("[ToolRouter] Initializing tool router...");
        reloadProviders();
    }

    @PreDestroy
    public void cleanup() {
        log.info("[ToolRouter] Cleaning up providers...");
        providerCache.values().forEach(ToolProvider::shutdown);
        providerCache.clear();
        toolRouteTable.clear();
    }

    /**
     * 重新加载所有 Provider
     */
    public synchronized void reloadProviders() {
        log.info("[ToolRouter] Reloading all providers...");

        // 关闭现有 Provider
        providerCache.values().forEach(ToolProvider::shutdown);
        providerCache.clear();
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
                providerCache.size(), toolRouteTable.size(), nativeToolScanner.listTools().size());
    }

    /**
     * 注册单个 Provider
     * 工具 ID 格式：{providerName}::{toolName}
     * 同名 Provider 会被覆盖
     */
    public void registerProvider(ToolProviderConfig config) throws Exception {
        String providerId = config.getId();
        String providerName = config.getName(); // 使用 name 作为前缀

        // 如果已存在同名 Provider，先关闭（覆盖逻辑）
        ToolProvider existing = providerCache.remove(providerId);
        if (existing != null) {
            log.info("[ToolRouter] Overwriting existing provider: {}", providerName);
            existing.shutdown();
            // 移除旧的路由（按 providerId）
            toolRouteTable.entrySet().removeIf(entry -> entry.getValue().equals(providerId));
        }

        // 创建新的 Provider
        ToolProvider provider = createProvider(config);
        provider.initialize(config);

        // 注册到缓存
        providerCache.put(providerId, provider);

        // 更新路由表（工具 ID 加前缀：providerName::toolName）
        for (ToolDefinition tool : provider.listTools()) {
            String originalToolName = tool.getName();
            String globalToolId = providerName + "::" + originalToolName;

            // 更新工具定义的名称为全局 ID
            tool.setName(globalToolId);

            // 注册路由
            if (toolRouteTable.containsKey(globalToolId)) {
                log.warn("[ToolRouter] Tool ID conflict: {} (will be overwritten)", globalToolId);
            }
            toolRouteTable.put(globalToolId, providerId);
        }

        // 更新配置状态
        configService.updateConnectionStatus(providerId, true, provider.listTools().size(), null);

        log.info("[ToolRouter] Registered provider: {} ({}) with {} tools (prefix: {}::)",
                config.getName(), providerId, provider.listTools().size(), providerName);
    }

    /**
     * 注销 Provider
     */
    public void unregisterProvider(String providerId) {
        ToolProvider provider = providerCache.remove(providerId);
        if (provider != null) {
            provider.shutdown();
            // 移除路由
            toolRouteTable.entrySet().removeIf(entry -> entry.getValue().equals(providerId));
            log.info("[ToolRouter] Unregistered provider: {}", providerId);
        }
    }

    /**
     * 创建 Provider 实例（支持 STDIO、REMOTE_SSE、STREAMABLE_HTTP）
     */
    private ToolProvider createProvider(ToolProviderConfig config) {
        return switch (config.getType()) {
            case STDIO -> new StdioToolProvider();
            case REMOTE_SSE -> new RemoteSseToolProvider();
            case STREAMABLE_HTTP -> new StreamableHttpToolProvider();
        };
    }

    /**
     * 调用工具
     * 工具名称格式：{providerName}::{toolName}
     * 需要提取原始工具名称传递给 Provider
     */
    public ToolCallResult callTool(String globalToolId, Map<String, Object> arguments, String sessionId) {
        // 1. 先检查 Native 工具
        if (nativeToolScanner.hasTool(globalToolId)) {
            log.info("[ToolRouter] Routing to native tool: {}", globalToolId);
            // 设置 SessionContext，供 native 工具使用
            try {
                if (sessionId != null) {
                    com.agent.mcpserver.context.SessionContext.setSessionId(sessionId);
                }
                // 从 arguments 中提取 stepIndex（如果有的话）
                Object stepIndexObj = arguments.get("stepIndex");
                if (stepIndexObj instanceof Integer) {
                    com.agent.mcpserver.context.SessionContext.setStepIndex((Integer) stepIndexObj);
                }
                return nativeToolScanner.callTool(globalToolId, arguments);
            } finally {
                com.agent.mcpserver.context.SessionContext.clear();
            }
        }

        // 2. 再查 Provider
        String providerId = toolRouteTable.get(globalToolId);
        if (providerId == null) {
            log.warn("[ToolRouter] Tool not found: {}", globalToolId);
            return ToolCallResult.error("Tool not found: " + globalToolId);
        }

        ToolProvider provider = providerCache.get(providerId);
        if (provider == null || !provider.isConnected()) {
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
        return provider.callTool(originalToolName, arguments, sessionId);
    }

    /**
     * 列出所有可用工具（包括 Native 工具）
     */
    public List<ToolDefinition> listAllTools() {
        List<ToolDefinition> all = new ArrayList<>();

        // 1. Native 工具（自动扫描）
        all.addAll(nativeToolScanner.listTools());

        // 2. STDIO / REMOTE_SSE 工具（数据库配置）
        providerCache.values().stream()
                .filter(ToolProvider::isConnected)
                .flatMap(p -> p.listTools().stream())
                .forEach(all::add);

        return all;
    }

    /**
     * 列出指定 Provider 的工具
     */
    public List<ToolDefinition> listToolsByProvider(String providerId) {
        // 检查是否是 Native
        if ("native".equals(providerId)) {
            return nativeToolScanner.listTools();
        }

        ToolProvider provider = providerCache.get(providerId);
        if (provider == null) {
            return List.of();
        }
        return provider.listTools();
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
                    null, // Native 没有 ProviderType
                    true,
                    nativeToolCount
            ));
        }

        // 2. 其他 Providers
        providerCache.values().stream()
                .map(p -> new ProviderInfo(
                        p.getId(),
                        p.getName(),
                        p.getType(),
                        p.isConnected(),
                        p.listTools().size()
                ))
                .forEach(providers::add);

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
    public Optional<ToolDefinition> getTool(String toolName) {
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

        ToolProvider provider = providerCache.get(providerId);
        if (provider == null) {
            return Optional.empty();
        }

        return provider.listTools().stream()
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