package com.agent.api.controller;

import com.agent.api.response.ApiResponse;
import com.agent.domain.token.*;
import com.agent.application.service.TokenUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Token使用统计控制器
 * 提供3个维度的统计查询API
 */
@Slf4j
@RestController
@RequestMapping("/api/token-usage")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    // ============= 维度1：成本与模型维度 =============

    /**
     * Provider费用占比统计
     * GET /api/token-usage/provider-cost?startDate=2024-01-01&endDate=2024-01-31
     */
    @GetMapping("/provider-cost")
    public ApiResponse<List<ProviderCostDTO>> getProviderCostDistribution(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        try {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(LocalTime.MAX);

            List<ProviderCostDTO> data = tokenUsageService.getProviderCostDistribution(start, end);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("Get provider cost distribution failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 模型消耗排行
     * GET /api/token-usage/top-models?startDate=2024-01-01&endDate=2024-01-31&limit=10
     */
    @GetMapping("/top-models")
    public ApiResponse<List<ModelUsageDTO>> getTopModelsByUsage(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        try {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(LocalTime.MAX);

            List<ModelUsageDTO> data = tokenUsageService.getTopModelsByUsage(start, end, limit);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("Get top models failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 每日成本趋势
     * GET /api/token-usage/daily-cost?startDate=2024-01-01&endDate=2024-01-31
     */
    @GetMapping("/daily-cost")
    public ApiResponse<List<DailyCostDTO>> getDailyCostTrend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        try {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(LocalTime.MAX);

            List<DailyCostDTO> data = tokenUsageService.getDailyCostTrend(start, end);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("Get daily cost trend failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ============= 维度2：会话与任务维度 =============

    /**
     * Top N昂贵会话
     * GET /api/token-usage/top-sessions?startDate=2024-01-01&endDate=2024-01-31&limit=10
     */
    @GetMapping("/top-sessions")
    public ApiResponse<List<SessionCostDTO>> getTopExpensiveSessions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        try {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(LocalTime.MAX);

            List<SessionCostDTO> data = tokenUsageService.getTopExpensiveSessions(start, end, limit);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("Get top sessions failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ============= 维度3：Agent效能维度 =============

    /**
     * Agent Token消耗排行
     * GET /api/token-usage/top-agents?startDate=2024-01-01&endDate=2024-01-31&limit=10
     */
    @GetMapping("/top-agents")
    public ApiResponse<List<AgentUsageDTO>> getTopAgentsByUsage(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        try {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(LocalTime.MAX);

            List<AgentUsageDTO> data = tokenUsageService.getTopAgentsByUsage(start, end, limit);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("Get top agents by usage failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * Agent成本排行
     * GET /api/token-usage/top-agents-cost?startDate=2024-01-01&endDate=2024-01-31&limit=10
     */
    @GetMapping("/top-agents-cost")
    public ApiResponse<List<AgentCostDTO>> getTopAgentsByCost(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        try {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(LocalTime.MAX);

            List<AgentCostDTO> data = tokenUsageService.getTopAgentsByCost(start, end, limit);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("Get top agents by cost failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
