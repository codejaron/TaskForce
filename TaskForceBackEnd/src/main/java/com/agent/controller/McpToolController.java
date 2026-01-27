package com.agent.controller;

import com.agent.client.RemoteMcpClient;
import com.agent.dto.ApiResponse;
import com.agent.model.ToolInfo;
import com.agent.service.AgentToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具控制器
 * 代理到远程 mcp-server 服务
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class McpToolController {

    private final RemoteMcpClient remoteMcpClient;
    private final AgentToolService agentToolService;

    /**
     * 获取所有 MCP 服务器（Provider）列表
     */
    @GetMapping("/servers")
    public ApiResponse<List<Map<String, Object>>> listServers() {
        try {
            List<Map<String, Object>> servers = remoteMcpClient.listProviders();
            return ApiResponse.success(servers);
        } catch (Exception e) {
            log.error("List servers failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 注册新的 MCP 服务器（Provider）
     */
    @PostMapping("/servers")
    public ApiResponse<Map<String, Object>> registerServer(@RequestBody Map<String, Object> serverConfig) {
        try {
            Map<String, Object> result = remoteMcpClient.registerProvider(serverConfig);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Register server failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除 MCP 服务器（Provider）
     */
    @DeleteMapping("/servers/{serverId}")
    public ApiResponse<Map<String, Object>> deleteServer(@PathVariable String serverId) {
        try {
            // 先删除 mcp-server 中的 provider
            Map<String, Object> result = remoteMcpClient.deleteProvider(serverId);
            
            // 再删除本地的工具关联
            int removedToolsCount = agentToolService.removeToolsByServerId(serverId);
            log.info("Removed {} tool associations for server: {}", removedToolsCount, serverId);
            
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Delete server failed: {}", serverId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取所有可用工具列表（从 mcp-server）
     */
    @GetMapping("/tools")
    public ApiResponse<List<ToolInfo>> listTools() {
        try {
            List<ToolInfo> tools = remoteMcpClient.listTools();
            return ApiResponse.success(tools);
        } catch (Exception e) {
            log.error("List tools failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 调用工具（代理到 mcp-server）
     */
    @PostMapping("/tools/invoke")
    public ApiResponse<Map<String, Object>> invokeTool(@RequestBody Map<String, Object> body) {
        try {
            String toolId = (String) body.get("toolId");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) body.getOrDefault("arguments", Map.of());
            
            if (toolId == null || toolId.isBlank()) {
                return ApiResponse.error("toolId is required");
            }
            
            RemoteMcpClient.ToolCallResultDTO result = remoteMcpClient.callTool(toolId, args);
            
            return ApiResponse.success(Map.of(
                    "toolId", toolId,
                    "result", result.getTextContent(),
                    "isError", result.isError()
            ));
        } catch (Exception e) {
            log.error("Invoke tool failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除指定 Provider 的所有工具关联
     * 注意：实际的 Provider 管理在 mcp-server 中进行
     */
    @DeleteMapping("/providers/{providerId}")
    public ApiResponse<Map<String, Object>> removeProviderTools(@PathVariable String providerId) {
        try {
            log.info("Removing tool associations for provider: {}", providerId);

            // 删除所有 agent_tools 中该 provider 的工具关联
            // 工具 ID 格式：{providerId}::{toolName}
            int removedToolsCount = agentToolService.removeToolsByServerId(providerId);
            log.info("Removed {} tool associations for provider: {}", removedToolsCount, providerId);

            return ApiResponse.success(Map.of(
                    "success", true,
                    "removedToolAssociations", removedToolsCount
            ));
        } catch (Exception e) {
            log.error("Remove provider tools failed for providerId: {}", providerId, e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
