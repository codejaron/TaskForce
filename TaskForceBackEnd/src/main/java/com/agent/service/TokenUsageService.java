package com.agent.service;

import com.agent.api.dto.*;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.entity.TokenUsage;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.infrastructure.persistence.mapper.TokenUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Token使用统计服务
 * 提供Token消耗记录、查询和统计功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageMapper tokenUsageMapper;
    private final AgentMapper agentMapper;

    /**
     * 记录一次Token使用
     *
     * @param sessionId        会话ID
     * @param providerId       Provider ID
     * @param agentId          Agent ID
     * @param modelName        模型名称
     * @param promptTokens     提示Token数
     * @param completionTokens 完成Token数
     * @return 保存的TokenUsage记录
     */
    @Transactional
    public TokenUsage recordUsage(
            String sessionId,
            Long providerId,
            Long agentId,
            String modelName,
            Integer promptTokens,
            Integer completionTokens
    ) {
        int safePromptTokens = Math.max(0, promptTokens != null ? promptTokens : 0);
        int safeCompletionTokens = Math.max(0, completionTokens != null ? completionTokens : 0);

        TokenUsage usage = TokenUsage.builder()
                .sessionId(sessionId)
                .providerId(providerId)
                .agentId(agentId)
                .modelName(modelName)
                .promptTokens(safePromptTokens)
                .completionTokens(safeCompletionTokens)
                .totalTokens(safePromptTokens + safeCompletionTokens)
                .cost(BigDecimal.ZERO)  // 不再计算价格，设为0
                .build();

        tokenUsageMapper.insert(usage);
        log.info("Token usage recorded: session={}, agent={}, model={}, prompt={}, completion={}, total={}",
                sessionId, agentId, modelName, safePromptTokens, safeCompletionTokens, usage.getTotalTokens());

        return usage;
    }

    /**
     * 从 Spring AI Usage 对象落库（统一入口：Single Chat / Team / LlmAdapter）
     *
     * @param sessionId 会话 ID
     * @param agentId Agent ID
     * @param usage Spring AI usage 元数据
     * @return 是否成功记录
     */
    @Transactional
    public boolean recordUsageFromSpringUsage(String sessionId, Long agentId, Usage usage) {
        if (agentId == null) {
            log.warn("Skip token usage record because agentId is null: session={}", sessionId);
            return false;
        }
        if (usage == null) {
            return false;
        }

        int promptTokens = sanitizeNonNegative(usage.getPromptTokens());
        int completionTokens = sanitizeNonNegative(usage.getCompletionTokens());
        int totalTokens = sanitizeNonNegative(usage.getTotalTokens());

        if (totalTokens <= 0) {
            totalTokens = promptTokens + completionTokens;
        }

        if (totalTokens <= 0) {
            return false;
        }

        if (promptTokens + completionTokens != totalTokens) {
            completionTokens = Math.max(0, totalTokens - promptTokens);
            if (promptTokens + completionTokens != totalTokens) {
                promptTokens = Math.max(0, totalTokens - completionTokens);
            }
        }

        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            log.warn("Skip token usage record because agent not found: session={}, agentId={}", sessionId, agentId);
            return false;
        }

        recordUsage(
                sessionId,
                agent.getProviderId(),
                agentId,
                agent.getModel(),
                promptTokens,
                completionTokens
        );
        return true;
    }

    private int sanitizeNonNegative(Integer value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, value);
    }

    /**
     * 获取会话的总Token消耗
     *
     * @param sessionId 会话ID
     * @return 总Token数
     */
    public Long getSessionTotalTokens(String sessionId) {
        Long total = tokenUsageMapper.sumTokensBySession(sessionId);
        return total != null ? total : 0L;
    }

    /**
     * 获取会话的所有Token使用记录
     *
     * @param sessionId 会话ID
     * @return Token使用记录列表
     */
    public List<TokenUsage> getSessionUsageRecords(String sessionId) {
        return tokenUsageMapper.findBySessionId(sessionId);
    }

    /**
     * 获取Provider的总Token消耗
     *
     * @param providerId Provider ID
     * @return 总Token数
     */
    public Long getProviderTotalTokens(Long providerId) {
        Long total = tokenUsageMapper.sumTokensByProvider(providerId);
        return total != null ? total : 0L;
    }

    /**
     * 获取会话的总成本
     *
     * @param sessionId 会话ID
     * @return 总成本（美元）
     */
    public BigDecimal getSessionTotalCost(String sessionId) {
        BigDecimal cost = tokenUsageMapper.sumCostBySession(sessionId);
        return cost != null ? cost : BigDecimal.ZERO;
    }


    /**
     * Provider费用占比统计
     */
    public List<ProviderCostDTO> getProviderCostDistribution(LocalDateTime startDate, LocalDateTime endDate) {
        return tokenUsageMapper.sumCostByProvider(startDate, endDate);
    }

    /**
     * 模型消耗排行
     */
    public List<ModelUsageDTO> getTopModelsByUsage(LocalDateTime startDate, LocalDateTime endDate, Integer limit) {
        return tokenUsageMapper.sumTokensByModel(startDate, endDate, limit);
    }

    /**
     * 每日成本趋势
     */
    public List<DailyCostDTO> getDailyCostTrend(LocalDateTime startDate, LocalDateTime endDate) {
        return tokenUsageMapper.sumCostByDay(startDate, endDate);
    }


    /**
     * Top昂贵会话
     */
    public List<SessionCostDTO> getTopExpensiveSessions(LocalDateTime startDate, LocalDateTime endDate, Integer limit) {
        return tokenUsageMapper.getTopExpensiveSessions(startDate, endDate, limit);
    }


    /**
     * Agent Token消耗排行
     */
    public List<AgentUsageDTO> getTopAgentsByUsage(LocalDateTime startDate, LocalDateTime endDate, Integer limit) {
        return tokenUsageMapper.sumTokensByAgent(startDate, endDate, limit);
    }

    /**
     * Agent成本排行
     */
    public List<AgentCostDTO> getTopAgentsByCost(LocalDateTime startDate, LocalDateTime endDate, Integer limit) {
        return tokenUsageMapper.sumCostByAgent(startDate, endDate, limit);
    }
}
