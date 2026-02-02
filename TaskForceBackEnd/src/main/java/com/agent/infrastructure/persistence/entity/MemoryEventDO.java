package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memory_event")
public class MemoryEventDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("request_id")
    private String requestId;

    private String type;

    @TableField("content_text")
    private String contentText;

    @TableField("source_agent")
    private String sourceAgent;

    private Integer importance;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
