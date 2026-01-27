package com.agent.mcpserver.service;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.service.provider.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具路由器
 * 根据 tool_name 路由到对应的 Provider
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRouter {

    private final ApplicationContext applicationContext;
    private final ToolProviderConfigService configService;

    /**
     * Provider 缓存：providerId -> ToolProvider
     */
    private final Map<String, ToolProvider> providerCache = new ConcurrentHashMap<>();

    /**
     * 工具路由表：toolName -> providerId
     */
    private final Map<String, String> toolRouteTable = new ConcurrentHashMap<>();

    @PostConstruct
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

        log.info("[ToolRouter] Loaded {} providers with {} tools", 
                providerCache.size(), toolRouteTable.size());
    }

    /**
     * 注册单个 Provider
     */
    public void registerProvider(ToolProviderConfig config) throws Exception {
        String providerId = config.getId();

        // 如果已存在，先关闭
        ToolProvider existing = providerCache.remove(providerId);
        if (existing != null) {
            existing.shutdown();
            // 移除旧的路由
            toolRouteTable.entrySet().removeIf(entry -> entry.getValue().equals(providerId));
        }

        // 创建新的 Provider
        ToolProvider provider = createProvider(config);
        provider.initialize(config);

        // 注册到缓存
        providerCache.put(providerId, provider);

        // 更新路由表
        for (ToolDefinition tool : provider.listTools()) {
            String toolName = tool.getName();
            if (toolRouteTable.containsKey(toolName)) {
                log.warn("[ToolRouter] Tool name conflict: {} (existing provider: {}, new provider: {})", 
                        toolName, toolRouteTable.get(toolName), providerId);
            }
            toolRouteTable.put(toolName, providerId);
        }

        // 更新配置状态
        configService.updateConnectionStatus(providerId, true, provider.listTools().size(), null);

        log.info("[ToolRouter] Registered provider: {} ({}) with {} tools", 
                config.getName(), providerId, provider.listTools().size());
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
     * 创建 Provider 实例
     */
    private ToolProvider createProvider(ToolProviderConfig config) {
        return switch (config.getType()) {
            case STDIO -> new StdioToolProvider();
            case NATIVE -> new NativeJavaToolProvider(applicationContext);
            case REMOTE_SSE -> new RemoteSseToolProvider();
        };
    }

    /**
     * 调用工具
     */
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments, String sessionId) {
        String providerId = toolRouteTable.get(toolName);
        if (providerId == null) {
            log.warn("[ToolRouter] Tool not found: {}", toolName);
            return ToolCallResult.error("Tool not found: " + toolName);
        }

        ToolProvider provider = providerCache.get(providerId);
        if (provider == null || !provider.isConnected()) {
            log.warn("[ToolRouter] Provider not available: {}", providerId);
            return ToolCallResult.error("Provider not available: " + providerId);
        }

        log.info("[ToolRouter] Routing tool call: {} -> {}", toolName, providerId);
        return provider.callTool(toolName, arguments, sessionId);
    }

    /**
     * 列出所有可用工具
     */
    public List<ToolDefinition> listAllTools() {
        return providerCache.values().stream()
                .filter(ToolProvider::isConnected)
                .flatMap(p -> p.listTools().stream())
                .collect(Collectors.toList());
    }

    /**
     * 列出指定 Provider 的工具
     */
    public List<ToolDefinition> listToolsByProvider(String providerId) {
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
        return providerCache.values().stream()
                .map(p -> new ProviderInfo(
                        p.getId(),
                        p.getName(),
                        p.getType(),
                        p.isConnected(),
                        p.listTools().size()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String toolName) {
        return toolRouteTable.containsKey(toolName);
    }

    /**
     * 获取工具定义
     */
    public Optional<ToolDefinition> getTool(String toolName) {
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
