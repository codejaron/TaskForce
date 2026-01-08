package com.agent.mcp.json;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON 配置数据模型
 */
@Data
public class McpConfig {
    private Map<String, ServerConfig> mcpServers = new HashMap<>();
    private GlobalSettings globalSettings = new GlobalSettings();

    @Data
    public static class ServerConfig {
        private String command;
        private List<String> args = new ArrayList<>();
        private String description;
        private boolean enabled = true;
        private Map<String, String> env = new HashMap<>();
    }

    @Data
    public static class GlobalSettings {
        private boolean autoReload = true;
        private int reloadInterval = 5000;
        private String defaultScope = "system";  // system 或 agent
    }
}
