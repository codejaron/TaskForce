package com.agent.mcpserver.controller;

import com.agent.mcpserver.dto.ApiResponse;
import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.service.ToolProviderConfigService;
import com.agent.mcpserver.service.ToolRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工具提供者管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/providers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProviderController {

    private final ToolRouter toolRouter;
    private final ToolProviderConfigService configService;

    /**
     * 获取所有提供者列表
     */
    @GetMapping
    public ApiResponse<List<ToolRouter.ProviderInfo>> listProviders() {
        try {
            List<ToolRouter.ProviderInfo> providers = toolRouter.listProviders();
            return ApiResponse.success(providers);
        } catch (Exception e) {
            log.error("[ProviderController] List providers failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取提供者的工具列表
     */
    @GetMapping("/{providerId}/tools")
    public ApiResponse<List<ToolDefinition>> listToolsByProvider(@PathVariable String providerId) {
        try {
            List<ToolDefinition> tools = toolRouter.listToolsByProvider(providerId);
            return ApiResponse.success(tools);
        } catch (Exception e) {
            log.error("[ProviderController] List tools by provider failed: {}", providerId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 添加新的提供者
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> addProvider(@RequestBody ToolProviderConfig config) {
        try {
            log.info("[ProviderController] Adding provider: {} ({})", config.getName(), config.getType());
            
            // 保存配置
            ToolProviderConfig savedConfig = configService.addConfig(config);
            
            // 注册到路由器
            toolRouter.registerProvider(savedConfig);

            return ApiResponse.success(Map.of(
                    "success", true,
                    "providerId", savedConfig.getId(),
                    "toolCount", toolRouter.listToolsByProvider(savedConfig.getId()).size()
            ));
        } catch (Exception e) {
            log.error("[ProviderController] Add provider failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除提供者
     */
    @DeleteMapping("/{providerId}")
    public ApiResponse<Map<String, Object>> deleteProvider(@PathVariable String providerId) {
        try {
            log.info("[ProviderController] Deleting provider: {}", providerId);

            // 从路由器注销
            toolRouter.unregisterProvider(providerId);

            // 如果是数据库配置，删除记录
            if (!providerId.startsWith("file::")) {
                configService.deleteConfig(providerId);
            }

            return ApiResponse.success(Map.of("success", true));
        } catch (Exception e) {
            log.error("[ProviderController] Delete provider failed: {}", providerId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 测试提供者连接
     */
    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> testConnection(@RequestBody ToolProviderConfig config) {
        try {
            log.info("[ProviderController] Testing provider: {} ({})", config.getName(), config.getType());
            
            // 创建临时 ID 用于测试
            String testId = "test_" + System.currentTimeMillis();
            config.setId(testId);

            // 尝试注册
            toolRouter.registerProvider(config);
            int toolCount = toolRouter.listToolsByProvider(testId).size();

            // 清理测试 Provider
            toolRouter.unregisterProvider(testId);

            return ApiResponse.success(Map.of(
                    "success", true,
                    "message", "Connection successful",
                    "toolCount", toolCount
            ));
        } catch (Exception e) {
            log.error("[ProviderController] Test connection failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 重新加载所有提供者
     */
    @PostMapping("/reload")
    public ApiResponse<Map<String, Object>> reloadProviders() {
        try {
            log.info("[ProviderController] Reloading all providers...");
            
            toolRouter.reloadProviders();
            
            List<ToolRouter.ProviderInfo> providers = toolRouter.listProviders();
            int totalTools = toolRouter.listAllTools().size();

            return ApiResponse.success(Map.of(
                    "success", true,
                    "providerCount", providers.size(),
                    "toolCount", totalTools
            ));
        } catch (Exception e) {
            log.error("[ProviderController] Reload providers failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
