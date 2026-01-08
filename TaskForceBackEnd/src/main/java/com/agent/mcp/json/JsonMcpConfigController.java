package com.agent.mcp.json;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * JSON MCP 配置 HTTP API
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp/json-config")
@RequiredArgsConstructor
public class JsonMcpConfigController {

    private final JsonMcpConfigService configService;

    /**
     * 手动重新加载配置
     */
    @PostMapping("/reload")
    public ResponseEntity<String> reloadConfig() {
        try {
            configService.loadConfig(Path.of(configService.getConfigPath()));
            return ResponseEntity.ok("Config reloaded successfully");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Failed to reload: " + e.getMessage());
        }
    }

    /**
     * 查看当前加载的服务器
     */
    @GetMapping("/servers")
    public ResponseEntity<Set<String>> listServers() {
        return ResponseEntity.ok(configService.getLoadedServerIds());
    }

    /**
     * 添加工具（通过 JSON 贴入）
     */
    @PostMapping("/add")
    public ResponseEntity<?> addTool(@RequestBody AddToolRequest request) {
        try {
            // 验证工具 key
            if (request.getToolKey() == null || request.getToolKey().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Tool key is required"
                ));
            }

            // 验证配置
            if (request.getConfig() == null || request.getConfig().getCommand() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Invalid tool configuration"
                ));
            }

            configService.addTool(request.getToolKey(), request.getConfig());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Tool added successfully",
                "toolKey", request.getToolKey()
            ));
        } catch (Exception e) {
            log.error("Failed to add tool", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 删除工具
     */
    @DeleteMapping("/remove/{toolKey}")
    public ResponseEntity<?> removeTool(@PathVariable String toolKey) {
        try {
            configService.removeTool(toolKey);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Tool removed successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Failed to remove tool", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 更新工具
     */
    @PutMapping("/update/{toolKey}")
    public ResponseEntity<?> updateTool(
        @PathVariable String toolKey,
        @RequestBody McpConfig.ServerConfig config
    ) {
        try {
            configService.updateTool(toolKey, config);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Tool updated successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Failed to update tool", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 获取当前配置
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        try {
            McpConfig config = configService.getCurrentConfig();
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Failed to get config", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 批量导入工具（从完整 JSON）
     */
    @PostMapping("/import")
    public ResponseEntity<?> importConfig(@RequestBody McpConfig config) {
        try {
            // 验证配置
            if (config.getMcpServers() == null || config.getMcpServers().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "No tools to import"
                ));
            }

            // 逐个添加工具
            int added = 0;
            for (Map.Entry<String, McpConfig.ServerConfig> entry : config.getMcpServers().entrySet()) {
                try {
                    configService.addTool(entry.getKey(), entry.getValue());
                    added++;
                } catch (Exception e) {
                    log.warn("Failed to import tool: {}", entry.getKey(), e);
                }
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Imported " + added + " tools",
                "count", added
            ));
        } catch (Exception e) {
            log.error("Failed to import config", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
}
