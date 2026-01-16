package com.agent.mapper;

import com.agent.entity.ToolCall;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 工具调用记录 Mapper
 */
@Mapper
public interface ToolCallMapper extends BaseMapper<ToolCall> {

    @Select("SELECT * FROM tool_calls WHERE session_id = #{sessionId} ORDER BY created_at ASC, sequence ASC")
    List<ToolCall> selectBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM tool_calls WHERE step_id = #{stepId} ORDER BY sequence ASC")
    List<ToolCall> selectByStepId(@Param("stepId") String stepId);

    @Select("SELECT * FROM tool_calls WHERE tool_call_id = #{toolCallId}")
    ToolCall selectByToolCallId(@Param("toolCallId") String toolCallId);

    @Update("UPDATE tool_calls SET tool_result = #{toolResult}, status = #{status}, " +
            "error_message = #{errorMessage}, completed_at = #{completedAt}, duration_ms = #{durationMs} " +
            "WHERE tool_call_id = #{toolCallId}")
    int updateByToolCallId(@Param("toolCallId") String toolCallId,
                           @Param("toolResult") String toolResult,
                           @Param("status") String status,
                           @Param("errorMessage") String errorMessage,
                           @Param("completedAt") java.time.LocalDateTime completedAt,
                           @Param("durationMs") Long durationMs);
}
