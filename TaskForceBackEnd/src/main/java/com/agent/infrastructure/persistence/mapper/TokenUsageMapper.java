package com.agent.infrastructure.persistence.mapper;

import com.agent.api.dto.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.infrastructure.persistence.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token使用统计Mapper
 * 提供Token统计的数据库操作
 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {

    /**
     * 统计会话的总Token消耗
     *
     * @param sessionId 会话ID
     * @return 总Token数
     */
    @Select("SELECT SUM(total_tokens) FROM token_usage WHERE session_id = #{sessionId}")
    Long sumTokensBySession(@Param("sessionId") String sessionId);

    /**
     * 统计Provider的总Token消耗
     *
     * @param providerId Provider ID
     * @return 总Token数
     */
    @Select("SELECT SUM(total_tokens) FROM token_usage WHERE provider_id = #{providerId}")
    Long sumTokensByProvider(@Param("providerId") Long providerId);

    /**
     * 获取会话的所有Token使用记录（按时间倒序）
     *
     * @param sessionId 会话ID
     * @return Token使用记录列表
     */
    @Select("SELECT * FROM token_usage WHERE session_id = #{sessionId} ORDER BY created_at DESC")
    List<TokenUsage> findBySessionId(@Param("sessionId") String sessionId);

    /**
     * 统计会话的总成本
     *
     * @param sessionId 会话ID
     * @return 总成本（美元）
     */
    @Select("SELECT SUM(cost) FROM token_usage WHERE session_id = #{sessionId}")
    java.math.BigDecimal sumCostBySession(@Param("sessionId") String sessionId);

    // ============= 维度1：成本与模型维度 =============

    /**
     * Provider费用占比统计
     */
    @Select("""
        SELECT
            p.id as providerId, p.name as providerName,
            SUM(t.cost) as totalCost, SUM(t.total_tokens) as totalTokens,
            COUNT(*) as callCount
        FROM token_usage t
        LEFT JOIN llm_providers p ON t.provider_id = p.id
        WHERE t.created_at BETWEEN #{startDate} AND #{endDate}
        GROUP BY t.provider_id, p.name
        ORDER BY totalCost DESC
    """)
    List<ProviderCostDTO> sumCostByProvider(@Param("startDate") LocalDateTime startDate,
                                            @Param("endDate") LocalDateTime endDate);

    /**
     * 模型消耗排行
     */
    @Select("""
        SELECT
            model_name as modelName,
            SUM(prompt_tokens) as totalPromptTokens,
            SUM(completion_tokens) as totalCompletionTokens,
            SUM(total_tokens) as totalTokens,
            SUM(cost) as totalCost,
            COUNT(*) as callCount
        FROM token_usage
        WHERE created_at BETWEEN #{startDate} AND #{endDate}
        GROUP BY model_name
        ORDER BY totalTokens DESC
        LIMIT #{limit}
    """)
    List<ModelUsageDTO> sumTokensByModel(@Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate,
                                         @Param("limit") Integer limit);

    /**
     * 每日成本趋势
     */
    @Select("""
        SELECT
            DATE(created_at) as date,
            SUM(cost) as totalCost,
            SUM(total_tokens) as totalTokens,
            COUNT(*) as callCount
        FROM token_usage
        WHERE created_at BETWEEN #{startDate} AND #{endDate}
        GROUP BY DATE(created_at)
        ORDER BY date ASC
    """)
    List<DailyCostDTO> sumCostByDay(@Param("startDate") LocalDateTime startDate,
                                    @Param("endDate") LocalDateTime endDate);

    // ============= 维度2：会话与任务维度 =============

    /**
     * Top昂贵会话
     */
    @Select("""
        SELECT
            t.session_id as sessionId, s.name as sessionName,
            SUM(t.cost) as totalCost, SUM(t.total_tokens) as totalTokens,
            COUNT(*) as callCount
        FROM token_usage t
        LEFT JOIN sessions s ON t.session_id = s.id
        WHERE t.created_at BETWEEN #{startDate} AND #{endDate}
        GROUP BY t.session_id, s.name
        ORDER BY totalCost DESC
        LIMIT #{limit}
    """)
    List<SessionCostDTO> getTopExpensiveSessions(@Param("startDate") LocalDateTime startDate,
                                                 @Param("endDate") LocalDateTime endDate,
                                                 @Param("limit") Integer limit);

    // ============= 维度3：Agent效能维度 =============

    /**
     * Agent Token消耗排行
     */
    @Select("""
        SELECT
            t.agent_id as agentId, a.name as agentName,
            SUM(t.prompt_tokens) as totalPromptTokens,
            SUM(t.completion_tokens) as totalCompletionTokens,
            SUM(t.total_tokens) as totalTokens,
            SUM(t.cost) as totalCost,
            COUNT(*) as callCount
        FROM token_usage t
        LEFT JOIN agents a ON t.agent_id = a.id
        WHERE t.created_at BETWEEN #{startDate} AND #{endDate}
        GROUP BY t.agent_id, a.name
        ORDER BY totalTokens DESC
        LIMIT #{limit}
    """)
    List<AgentUsageDTO> sumTokensByAgent(@Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate,
                                         @Param("limit") Integer limit);

    /**
     * Agent成本排行
     */
    @Select("""
        SELECT
            t.agent_id as agentId, a.name as agentName,
            SUM(t.cost) as totalCost, SUM(t.total_tokens) as totalTokens,
            COUNT(*) as callCount
        FROM token_usage t
        LEFT JOIN agents a ON t.agent_id = a.id
        WHERE t.created_at BETWEEN #{startDate} AND #{endDate}
        GROUP BY t.agent_id, a.name
        ORDER BY totalCost DESC
        LIMIT #{limit}
    """)
    List<AgentCostDTO> sumCostByAgent(@Param("startDate") LocalDateTime startDate,
                                     @Param("endDate") LocalDateTime endDate,
                                     @Param("limit") Integer limit);
}
