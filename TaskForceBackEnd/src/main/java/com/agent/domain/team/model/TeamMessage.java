package com.agent.domain.team.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 团队消息值对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamMessage {

    /**
     * 发送者
     */
    private String from;

    /**
     * 接收者
     */
    private String to;

    /**
     * 消息内容
     */
    private String text;

    /**
     * 消息类型
     */
    private String type;

    /**
     * 时间戳
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
