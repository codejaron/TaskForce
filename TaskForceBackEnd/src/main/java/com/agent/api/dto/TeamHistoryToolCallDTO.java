package com.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Team 会话历史工具调用 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamHistoryToolCallDTO {

    private String toolCallId;
    private String stepId;
    private Integer sequence;
    private String instanceId;
    private String roundId;
    private String toolName;
    private String serverName;
    private String toolArgs;
    private String toolResult;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private String startedAt;
    private String completedAt;
    private String syncStatus;
    private String syncError;
    private String syncedAt;
}
