package com.agent.mcpserver.controller;

import com.agent.mcpserver.dto.ApiResponse;
import com.agent.mcpserver.dto.ToolCallRequest;
import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolVO;
import com.agent.mcpserver.service.ToolRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工具管理控制器
 * 提供 REST API 接口用于管理和调用工具
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ToolController {

    private final ToolRouter toolRouter;

    /**
     * 获取所有可用工具列表
     * GET /tools
     */
    @GetMapping
    public ApiResponse<List<ToolVO>> listTools() {
        try {
            List<ToolVO> tools = toolRouter.listAllTools();
            return ApiResponse.success(tools);
        } catch (Exception e) {
            log.error("[ToolController] List tools failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取单个工具定义
     * GET /tools/{name}
     */
    @GetMapping("/{name}")
    public ApiResponse<ToolVO> getTool(@PathVariable String name) {
        try {
            return toolRouter.getTool(name)
                    .map(ApiResponse::success)
                    .orElse(ApiResponse.error("Tool not found: " + name));
        } catch (Exception e) {
            log.error("[ToolController] Get tool failed: {}", name, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 调用工具（REST API 方式）
     * POST /tools/call
     */
    @PostMapping("/call")
    public ApiResponse<ToolCallResult> callTool(@RequestBody ToolCallRequest request) {
        try {
            log.info("[ToolController] Tool call: name={}", request.getName());
            
            ToolCallResult result = toolRouter.callTool(
                    request.getName(),
                    request.getArguments(),
                    request.getSessionId()
            );
            
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[ToolController] Tool call failed: {}", request.getName(), e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 快速调用工具
     * POST /tools/{name}/invoke
     */
    @PostMapping("/{name}/invoke")
    public ApiResponse<ToolCallResult> invokeTool(
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> arguments
    ) {
        try {
            log.info("[ToolController] Tool invoke: name={}", name);
            
            ToolCallResult result = toolRouter.callTool(name, arguments, null);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[ToolController] Tool invoke failed: {}", name, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取工具统计信息
     * GET /tools/stats
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        try {
            List<ToolRouter.ProviderInfo> providers = toolRouter.listProviders();
            List<ToolVO> tools = toolRouter.listAllTools();

            Map<String, Object> stats = Map.of(
                    "providerCount", providers.size(),
                    "toolCount", tools.size(),
                    "providers", providers
            );

            return ApiResponse.success(stats);
        } catch (Exception e) {
            log.error("[ToolController] Get stats failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
