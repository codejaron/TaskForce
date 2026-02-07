package com.agent.infrastructure.persistence.mapper;

import com.agent.infrastructure.persistence.entity.ExecutionPlanStepDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 执行计划步骤 Mapper
 */
@Mapper
public interface ExecutionPlanStepMapper extends BaseMapper<ExecutionPlanStepDO> {

    /**
     * 根据计划ID查询所有步骤
     */
    @Select("SELECT * FROM execution_plan_step WHERE plan_id = #{planId} ORDER BY step_index ASC")
    List<ExecutionPlanStepDO> findByPlanId(@Param("planId") String planId);

    /**
     * 根据计划ID和步骤ID查询步骤
     */
    @Select("SELECT * FROM execution_plan_step WHERE plan_id = #{planId} AND step_id = #{stepId}")
    ExecutionPlanStepDO findByPlanIdAndStepId(@Param("planId") String planId, @Param("stepId") String stepId);

    /**
     * 根据会话ID查询所有步骤
     */
    @Select("SELECT * FROM execution_plan_step WHERE session_id = #{sessionId} ORDER BY step_index ASC")
    List<ExecutionPlanStepDO> findBySessionId(@Param("sessionId") String sessionId);

    /**
     * 根据计划ID和层级索引查询步骤
     */
    @Select("SELECT * FROM execution_plan_step WHERE plan_id = #{planId} AND layer_index = #{layerIndex} ORDER BY step_index ASC")
    List<ExecutionPlanStepDO> findByPlanIdAndLayerIndex(@Param("planId") String planId, @Param("layerIndex") Integer layerIndex);

    /**
     * 根据计划ID和状态查询步骤
     */
    @Select("SELECT * FROM execution_plan_step WHERE plan_id = #{planId} AND status = #{status} ORDER BY step_index ASC")
    List<ExecutionPlanStepDO> findByPlanIdAndStatus(@Param("planId") String planId, @Param("status") String status);
}
