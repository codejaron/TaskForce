package com.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Team 会话历史查询响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamSessionHistoryDTO {

    private List<TeamHistoryMessageDTO> messages;
    private List<TeamHistoryToolCallDTO> toolCalls;
    private String nextBefore;
}
