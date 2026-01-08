package com.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息总结实体 - 存储对话历史的压缩摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("message_summary")
public class MessageSummary {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("summary_text")
    private String summaryText;

    @TableField("message_count")
    private Integer messageCount;  // 这个摘要涵盖了多少条消息

    @TableField("start_message_id")
    private Long startMessageId;  // 起始消息ID

    @TableField("end_message_id")
    private Long endMessageId;  // 结束消息ID

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

