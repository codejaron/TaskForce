package com.agent.service;

import com.agent.entity.TokenUsage;
import com.agent.mapper.TokenUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * 记录一次Token使用
     *
     * @param sessionId        会话ID
     * @param providerId       Provider ID
     * @param modelName        模型名称
     * @param promptTokens     提示Token数
     * @param completionTokens 完成Token数
     * @return 保存的TokenUsage记录
     */
    @Transactional
    public TokenUsage recordUsage(
            String sessionId,
            Long providerId,
            String modelName,
            Integer promptTokens,
            Integer completionTokens
    ) {
        TokenUsage usage = TokenUsage.builder()
                .sessionId(sessionId)
                .providerId(providerId)
                .modelName(modelName)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .cost(calculateCost(modelName, promptTokens, completionTokens))
                .build();

        tokenUsageMapper.insert(usage);
        log.info("Token usage recorded: session={}, model={}, prompt={}, completion={}, total={}",
                sessionId, modelName, promptTokens, completionTokens, usage.getTotalTokens());

        return usage;
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
     * 计算成本（预留，可根据模型定价计算）
     * 当前返回0，未来可根据实际定价表实现
     *
     * @param modelName        模型名称
     * @param promptTokens     提示Token数
     * @param completionTokens 完成Token数
     * @return 成本（美元）
     */
    private BigDecimal calculateCost(String modelName, Integer promptTokens, Integer completionTokens) {
        // TODO: 实现真实的成本计算逻辑
        // 示例定价（需要根据实际情况调整）：
        // GPT-4o: $2.5/1M input tokens, $10/1M output tokens
        // Claude Sonnet 3.5: $3/1M input, $15/1M output

        return BigDecimal.ZERO;
    }
}
