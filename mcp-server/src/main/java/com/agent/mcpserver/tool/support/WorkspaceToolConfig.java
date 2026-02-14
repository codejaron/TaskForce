package com.agent.mcpserver.tool.support;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作区工具配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "workspace")
public class WorkspaceToolConfig {

    /**
     * 代码工作区根目录。
     */
    private String basePath = "/Users/jaron/Downloads/test";

    /**
     * ripgrep 命令路径（默认 rg）。
     */
    private String ripgrepPath = "rg";

    /**
     * 输出最大行数。
     */
    private int maxOutputLines = 3000;

    /**
     * 输出最大字节数。
     */
    private int maxOutputBytes = 50000;

    /**
     * Bash 默认超时毫秒。
     */
    private long bashDefaultTimeoutMs = 120_000;

    /**
     * Read 默认读取行数。
     */
    private int readDefaultLimit = 2000;

    /**
     * Read 每行最大长度。
     */
    private int readMaxLineLength = 2000;
}
