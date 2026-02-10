package com.agent.api.controller;

import com.agent.api.dto.AgentToolDetail;
import com.agent.api.response.ApiResponse;
import com.agent.infrastructure.persistence.entity.AgentTool;
import com.agent.service.AgentToolService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent工具管理控制器
 * <p>
 * 提供Agent与MCP工具的关联管理API
 */
@Slf4j
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
@RequiredArgsConstructor
public class AgentToolController {

    private final AgentToolService agentToolService;

    /**
     * 获取Agent的所有工具（包含启用状态）
     * <p>
     * GET /api/agents/{agentId}/tools
     */
    @GetMapping
    public ApiResponse<List<AgentToolDetail>> getAgentTools(@PathVariable Long agentId) {
        try {
            List<AgentToolDetail> tools = agentToolService.getAgentToolDetails(agentId);
            return ApiResponse.success(tools);
        } catch (Exception e) {
            log.error("Get agent tools failed: agentId={}", agentId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 批量设置Agent的工具（替换现有配置）
     * <p>
     * PUT /api/agents/{agentId}/tools
     * Body: { "toolIds": ["serverId::toolName1", "serverId::toolName2"] }
     */
    @PutMapping
    public ApiResponse<Void> setAgentTools(
            @PathVariable Long agentId,
            @Valid @RequestBody SetToolsRequest request) {
        try {
            agentToolService.setAgentTools(agentId, request.getToolIds());
            return ApiResponse.success("工具配置已更新", null);
        } catch (Exception e) {
            log.error("Set agent tools failed: agentId={}", agentId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 为Agent添加单个工具
     * <p>
     * POST /api/agents/{agentId}/tools
     * Body: { "toolId": "serverId::toolName" }
     */
    @PostMapping
    public ApiResponse<AgentTool> addTool(
            @PathVariable Long agentId,
            @Valid @RequestBody AddToolRequest request) {
        try {
            AgentTool agentTool = agentToolService.addToolToAgent(agentId, request.getToolId());
            return ApiResponse.success("工具已添加", agentTool);
        } catch (Exception e) {
            log.error("Add tool failed: agentId={}, toolId={}", agentId, request.getToolId(), e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 移除Agent的工具
     * <p>
     * DELETE /api/agents/{agentId}/tools/{toolId}
     */
    @DeleteMapping("/{toolId}")
    public ApiResponse<Void> removeTool(
            @PathVariable Long agentId,
            @PathVariable String toolId) {
        try {
            agentToolService.removeToolFromAgent(agentId, toolId);
            return ApiResponse.success("工具已移除", null);
        } catch (Exception e) {
            log.error("Remove tool failed: agentId={}, toolId={}", agentId, toolId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 启用/禁用工具
     * <p>
     * PATCH /api/agents/{agentId}/tools/{toolId}
     * Body: { "enabled": true }
     */
    @PatchMapping("/{toolId}")
    public ApiResponse<Void> toggleTool(
            @PathVariable Long agentId,
            @PathVariable String toolId,
            @Valid @RequestBody ToggleToolRequest request) {
        try {
            agentToolService.setToolEnabled(agentId, toolId, request.getEnabled());
            String status = request.getEnabled() ? "启用" : "禁用";
            return ApiResponse.success("工具已" + status, null);
        } catch (Exception e) {
            log.error("Toggle tool failed: agentId={}, toolId={}", agentId, toolId, e);
            return ApiResponse.error(e.getMessage());
        }
    }


    /**
     * 清理无效的工具关联
     * <p>
     * POST /api/agents/{agentId}/tools/cleanup
     */
    @PostMapping("/cleanup")
    public ApiResponse<CleanupResponse> cleanupInvalidTools(@PathVariable Long agentId) {
        try {
            int cleaned = agentToolService.cleanupInvalidTools(agentId);
            CleanupResponse response = new CleanupResponse(cleaned);
            return ApiResponse.success("清理完成", response);
        } catch (Exception e) {
            log.error("Cleanup tools failed: agentId={}", agentId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ========== Request/Response DTOs ==========

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class SetToolsRequest {
        @NotEmpty(message = "工具ID列表不能为空")
        private List<String> toolIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class AddToolRequest {
        @NotBlank(message = "工具ID不能为空")
        private String toolId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ToggleToolRequest {
        private Boolean enabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class CleanupResponse {
        private int cleanedCount;
    }
}
