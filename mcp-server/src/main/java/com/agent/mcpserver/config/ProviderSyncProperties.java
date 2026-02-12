package com.agent.mcpserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Provider 同步广播配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.provider-sync")
public class ProviderSyncProperties {

    /**
     * Redis Pub/Sub 频道
     */
    private String channel = "mcp-provider-sync";

    /**
     * 当前实例 ID，用于忽略自身广播
     */
    private String instanceId = UUID.randomUUID().toString();
}
