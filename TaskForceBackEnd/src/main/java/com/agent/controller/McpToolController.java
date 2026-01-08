package com.agent.controller;

import com.agent.dto.ApiResponse;
import com.agent.mcp.McpToolRegistry;
import com.agent.mcp.json.JsonMcpConfigService;
import com.agent.model.McpServerDefinition;
import com.agent.model.ToolInfo;
import com.agent.service.AgentToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class McpToolController {

    private final McpToolRegistry mcpToolRegistry;
    private final JsonMcpConfigService jsonMcpConfigService;
    private final AgentToolService agentToolService;

    @GetMapping("/tools")
    public ApiResponse<List<ToolInfo>> listTools() {
        try {
            List<ToolInfo> tools = mcpToolRegistry.listAvailableTools();
            return ApiResponse.success(tools);
        } catch (Exception e) {
            log.error("List tools failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/servers/{serverId}/tools")
    public ApiResponse<List<ToolInfo>> listToolsByServer(@PathVariable String serverId) {
        try {
            List<ToolInfo> tools = mcpToolRegistry.listToolsByServer(serverId);
            return ApiResponse.success(tools);
        } catch (Exception e) {
            log.error("List tools by server failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/servers")
    public ApiResponse<List<McpServerDefinition>> listServers() {
        try {
            List<McpServerDefinition> servers = mcpToolRegistry.listServers();
            return ApiResponse.success(servers);
        } catch (Exception e) {
            log.error("List servers failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/servers")
    public ApiResponse<Map<String, Object>> registerServer(@RequestBody McpServerDefinition definition) {
        try {
            log.info("Registering MCP Server: {}", definition.getName());
            mcpToolRegistry.registerServer(definition);
            return ApiResponse.success(Map.of(
                    "success", true,
                    "serverId", definition.getId(),
                    "toolCount", mcpToolRegistry.listToolsByServer(definition.getId()).size()
            ));
        } catch (Exception e) {
            log.error("Failed to register MCP Server", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/servers/{serverId}")
    public ApiResponse<Map<String, Object>> unregisterServer(@PathVariable String serverId) {
        try {
            log.info("Unregistering MCP Server: {}", serverId);

            // Step 1: Remove all agent_tools associations for this server
            int removedToolsCount = agentToolService.removeToolsByServerId(serverId);
            log.info("Removed {} tool associations for server: {}", removedToolsCount, serverId);

            // Step 2: Unregister from memory (McpToolRegistry)
            mcpToolRegistry.unregisterServer(serverId);

            // Step 3: If it's a JSON-managed server, remove from config file
            boolean deletedFromConfig = false;
            if (serverId.startsWith("json::")) {
                String toolKey = serverId.substring(6); // Remove "json::" prefix
                log.info("Removing JSON config for tool: {}", toolKey);
                jsonMcpConfigService.removeTool(toolKey);
                log.info("Successfully deleted server from JSON config: {}", toolKey);
                deletedFromConfig = true;
            }

            return ApiResponse.success(Map.of(
                    "success", true,
                    "deletedFromConfig", deletedFromConfig,
                    "removedToolAssociations", removedToolsCount
            ));
        } catch (Exception e) {
            log.error("Unregister server failed for serverId: {}", serverId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/servers/test")
    public ApiResponse<Map<String, Object>> testConnection(@RequestBody McpServerDefinition definition) {
        try {
            log.info("Testing MCP Server connection: {}", definition.getName());
            String testId = "test_" + System.currentTimeMillis();
            definition.setId(testId);
            mcpToolRegistry.registerServer(definition);

            int toolCount = mcpToolRegistry.listToolsByServer(testId).size();

            mcpToolRegistry.unregisterServer(testId);

            return ApiResponse.success(Map.of(
                    "success", true,
                    "message", "Connection successful",
                    "toolCount", toolCount
            ));
        } catch (Exception e) {
            log.error("Connection test failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        try {
            return ApiResponse.success(Map.of(
                    "serverCount", mcpToolRegistry.getServerCount(),
                    "toolCount", mcpToolRegistry.getToolCount()
            ));
        } catch (Exception e) {
            log.error("Get stats failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // --- NEW: tool invoke endpoint for quick testing ---
    @PostMapping("/tools/invoke")
    public ApiResponse<Map<String, Object>> invokeTool(@RequestBody Map<String, Object> body) {
        try {
            String toolId = (String) body.get("toolId");
            String input = body.getOrDefault("input", "").toString();
            if (toolId == null || toolId.isBlank()) {
                return ApiResponse.error("toolId is required");
            }
            Optional<ToolCallback> cbOpt = mcpToolRegistry.getToolCallback(toolId);
            if (cbOpt.isEmpty()) {
                return ApiResponse.error("Tool not found: " + toolId);
            }
            ToolCallback cb = cbOpt.get();
            String result = cb.call(input);
            return ApiResponse.success(Map.of("toolId", toolId, "result", result));
        } catch (Exception e) {
            log.error("Invoke tool failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
