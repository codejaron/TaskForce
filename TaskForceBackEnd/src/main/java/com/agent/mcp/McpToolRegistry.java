package com.agent.mcp;

import com.agent.config.NonBlockingMcpToolCallback;
import com.agent.model.McpServerDefinition;
import com.agent.model.ToolInfo;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class McpToolRegistry {

    private final Map<String, McpSyncClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, ToolCallback> toolCallbackCache = new ConcurrentHashMap<>();
    private final Map<String, McpServerDefinition> serverDefinitions = new ConcurrentHashMap<>();
    private final Map<String, ToolInfo> toolInfoCache = new ConcurrentHashMap<>();
    private static final Duration DEFAULT_CLIENT_INIT_TIMEOUT = Duration.ofSeconds(30);

    @PostConstruct
    public void init() {
        log.info("MCP Tool Registry initialized");
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up MCP Tool Registry...");
        clientCache.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client", e);
            }
        });
        clientCache.clear();
        toolCallbackCache.clear();
    }

    public void registerServer(McpServerDefinition definition) {
        if (definition.getId() == null || definition.getId().trim().isEmpty()) {
            definition.setId(java.util.UUID.randomUUID().toString());
        }
        String serverId = definition.getId();
        log.info("Registering MCP Server: {} ({})", definition.getName(), serverId);

        try {
            // If there is already a server with this id, clean it up first to avoid stale processes
            McpSyncClient existing = clientCache.remove(serverId);
            if (existing != null) {
                try {
                    existing.close();
                } catch (Exception e) {
                    log.warn("Error closing existing MCP client for server {}", serverId, e);
                }
                serverDefinitions.remove(serverId);
                toolCallbackCache.keySet().removeIf(key -> key.startsWith(serverId + "::"));
                toolInfoCache.keySet().removeIf(key -> key.startsWith(serverId + "::"));
                log.info("Cleaned up previous MCP Server with id {} before re-registering", serverId);
            }

            McpSyncClient client = createClient(definition);
            client.initialize();
            clientCache.put(serverId, client);
            definition.setConnected(true);
            serverDefinitions.put(serverId, definition);

            SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(client);
            ToolCallback[] callbacks = provider.getToolCallbacks();

            for (ToolCallback callback : callbacks) {
                String toolName = callback.getName();
                String toolId = serverId + "::" + toolName;


                ToolCallback nonBlockingCallback = new NonBlockingMcpToolCallback(callback);
                toolCallbackCache.put(toolId, nonBlockingCallback);

                ToolInfo toolInfo = ToolInfo.builder()
                        .id(toolId)
                        .name(toolName)
                        .description(callback.getDescription())
                        .serverId(serverId)
                        .serverName(definition.getName())
                        .build();
                toolInfoCache.put(toolId, toolInfo);

                log.info("  Registered tool: {}", toolId);
            }

            log.info("MCP Server registered successfully with {} tools", callbacks.length);

        } catch (Exception e) {
            log.error("Failed to register MCP Server: {}", definition.getName(), e);
            definition.setConnected(false);
            throw new RuntimeException("Failed to register MCP Server", e);
        }
    }

    public void unregisterServer(String serverId) {
        log.info("Unregistering MCP Server: {}", serverId);

        McpSyncClient client = clientCache.remove(serverId);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client", e);
            }
        }

        serverDefinitions.remove(serverId);
        toolCallbackCache.keySet().removeIf(key -> key.startsWith(serverId + "::"));
        toolInfoCache.keySet().removeIf(key -> key.startsWith(serverId + "::"));
    }

    public Optional<ToolCallback> getToolCallback(String toolId) {
        return Optional.ofNullable(toolCallbackCache.get(toolId));
    }

    public ToolCallback[] getToolCallbacks(List<String> toolIds) {
        return toolIds.stream()
                .map(toolCallbackCache::get)
                .filter(Objects::nonNull)
                .toArray(ToolCallback[]::new);
    }

    public List<ToolInfo> listAvailableTools() {
        return new ArrayList<>(toolInfoCache.values());
    }

    public List<ToolInfo> listToolsByServer(String serverId) {
        return toolInfoCache.values().stream()
                .filter(tool -> tool.getServerId().equals(serverId))
                .collect(Collectors.toList());
    }

    public List<McpServerDefinition> listServers() {
        return new ArrayList<>(serverDefinitions.values());
    }

    public Optional<McpServerDefinition> getServer(String serverId) {
        return Optional.ofNullable(serverDefinitions.get(serverId));
    }

    public boolean isServerConnected(String serverId) {
        McpServerDefinition def = serverDefinitions.get(serverId);
        return def != null && Boolean.TRUE.equals(def.getConnected());
    }

    private McpSyncClient createClient(McpServerDefinition definition) {
        if (definition.getType() == McpServerDefinition.McpServerType.STDIO) {
            ServerParameters serverParams = ServerParameters.builder(definition.getCommand())
                    .args(definition.getArgs().toArray(new String[0]))
                    .build();

            StdioClientTransport transport = new StdioClientTransport(serverParams);
            log.info("Creating MCP client with timeout: {}s", DEFAULT_CLIENT_INIT_TIMEOUT.toSeconds());
            return McpClient.sync(transport)
                    .requestTimeout(DEFAULT_CLIENT_INIT_TIMEOUT)
                    .build();
        } else {
            throw new UnsupportedOperationException("SSE mode not yet implemented");
        }
    }

    public int getToolCount() {
        return toolCallbackCache.size();
    }

    public int getServerCount() {
        return serverDefinitions.size();
    }
}

