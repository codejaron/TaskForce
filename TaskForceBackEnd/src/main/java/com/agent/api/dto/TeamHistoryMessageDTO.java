package com.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Team 会话历史消息 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamHistoryMessageDTO {

    private Long id;
    private String role;
    private String messageType;
    private String agentName;
    private String content;
    private String createdAt;
}
