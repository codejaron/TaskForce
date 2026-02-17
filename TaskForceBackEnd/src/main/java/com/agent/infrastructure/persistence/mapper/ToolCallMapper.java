package com.agent.infrastructure.persistence.mapper;

import com.agent.infrastructure.persistence.entity.ToolCall;
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

    @Select("SELECT * FROM tool_calls WHERE session_id = #{sessionId} AND round_id = #{roundId} " +
            "ORDER BY created_at ASC, sequence ASC")
    List<ToolCall> selectBySessionAndRoundId(@Param("sessionId") String sessionId,
                                             @Param("roundId") String roundId);

    @Update("UPDATE tool_calls SET tool_result = #{toolResult}, status = #{status}, " +
            "error_message = #{errorMessage}, completed_at = #{completedAt}, duration_ms = #{durationMs} " +
            "WHERE tool_call_id = #{toolCallId}")
    int updateByToolCallId(@Param("toolCallId") String toolCallId,
                           @Param("toolResult") String toolResult,
                           @Param("status") String status,
                           @Param("errorMessage") String errorMessage,
                           @Param("completedAt") java.time.LocalDateTime completedAt,
                           @Param("durationMs") Long durationMs);

    @Update("UPDATE tool_calls SET file_path = #{filePath} WHERE tool_call_id = #{toolCallId}")
    int updateFilePath(@Param("toolCallId") String toolCallId, @Param("filePath") String filePath);

    @Update("UPDATE tool_calls SET sync_status = #{syncStatus}, sync_error = #{syncError}, synced_at = #{syncedAt} " +
            "WHERE session_id = #{sessionId} AND round_id = #{roundId}")
    int updateRoundSyncStatus(@Param("sessionId") String sessionId,
                              @Param("roundId") String roundId,
                              @Param("syncStatus") String syncStatus,
                              @Param("syncError") String syncError,
                              @Param("syncedAt") java.time.LocalDateTime syncedAt);
}
