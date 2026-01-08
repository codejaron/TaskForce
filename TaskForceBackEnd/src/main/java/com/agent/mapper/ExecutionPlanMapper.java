package com.agent.mapper;

import com.agent.entity.ExecutionPlanDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 执行计划 Mapper
 */
@Mapper
public interface ExecutionPlanMapper extends BaseMapper<ExecutionPlanDO> {

    @Select("SELECT * FROM execution_plan WHERE session_id = #{sessionId} ORDER BY updated_at DESC LIMIT 1")
    Optional<ExecutionPlanDO> findBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) > 0 FROM execution_plan WHERE session_id = #{sessionId}")
    boolean existsBySessionId(@Param("sessionId") String sessionId);
}
