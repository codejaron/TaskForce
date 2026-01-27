package com.agent.mcpserver.service.provider;

import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具提供者抽象基类
 */
@Slf4j
@Getter
public abstract class AbstractToolProvider implements ToolProvider {

    protected String id;
    protected String name;
    protected ToolProviderConfig.ProviderType type;
    protected boolean connected = false;
    protected ToolProviderConfig config;
    
    /**
     * 工具定义缓存
     */
    protected final Map<String, ToolDefinition> toolCache = new ConcurrentHashMap<>();

    @Override
    public void initialize(ToolProviderConfig config) throws Exception {
        this.config = config;
        this.id = config.getId();
        this.name = config.getName();
        this.type = config.getType();
        
        log.info("[{}] Initializing provider: {} ({})", type, name, id);
        
        try {
            doInitialize(config);
            this.connected = true;
            log.info("[{}] Provider initialized successfully: {} with {} tools", 
                    type, name, toolCache.size());
        } catch (Exception e) {
            this.connected = false;
            log.error("[{}] Failed to initialize provider: {}", type, name, e);
            throw e;
        }
    }

    /**
     * 子类实现具体初始化逻辑
     */
    protected abstract void doInitialize(ToolProviderConfig config) throws Exception;

    @Override
    public void shutdown() {
        log.info("[{}] Shutting down provider: {}", type, name);
        try {
            doShutdown();
        } catch (Exception e) {
            log.error("[{}] Error during shutdown: {}", type, name, e);
        }
        this.connected = false;
        this.toolCache.clear();
    }

    /**
     * 子类实现具体关闭逻辑
     */
    protected abstract void doShutdown();

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public List<ToolDefinition> listTools() {
        return List.copyOf(toolCache.values());
    }

    @Override
    public boolean hasTool(String toolName) {
        return toolCache.containsKey(toolName);
    }

    /**
     * 注册工具
     */
    protected void registerTool(ToolDefinition tool) {
        tool.setSourceType(convertProviderType(type));
        tool.setProviderId(id);
        toolCache.put(tool.getName(), tool);
        log.debug("[{}] Registered tool: {}", type, tool.getName());
    }

    /**
     * 转换提供者类型到工具来源类型
     */
    private ToolDefinition.ToolSourceType convertProviderType(ToolProviderConfig.ProviderType type) {
        return switch (type) {
            case STDIO -> ToolDefinition.ToolSourceType.STDIO;
            case REMOTE_SSE -> ToolDefinition.ToolSourceType.REMOTE_SSE;
        };
    }
}
