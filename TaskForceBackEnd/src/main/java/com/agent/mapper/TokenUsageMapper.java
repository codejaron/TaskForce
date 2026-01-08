package com.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
