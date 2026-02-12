package com.agent.mcpserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端池配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.client-pool")
public class McpClientPoolProperties {

    /**
     * STDIO 类型默认池大小
     */
    private int stdioDefaultSize = 3;

    /**
     * REMOTE_SSE 类型默认池大小
     */
    private int remoteSseDefaultSize = 4;

    /**
     * STREAMABLE_HTTP 类型默认池大小
     */
    private int streamableHttpDefaultSize = 8;

    /**
     * 借用客户端等待超时（秒）
     */
    private int acquireTimeoutSeconds = 8;

    /**
     * 池大小上限，避免误配置占用过多资源
     */
    private int maxPoolSize = 20;
}
