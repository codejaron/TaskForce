package com.agent.mcp.json;

import lombok.Data;

@Data
public class AddToolRequest {
    private String toolKey;
    private McpConfig.ServerConfig config;
}
