package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Session Artifact 实体
 * 对应 session_artifact 表
 * 用于存储会话中的结构化知识（黑板机制）
 */
@Data
@TableName("session_artifact")
public class SessionArtifact {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * Artifact 键名
     */
    private String artifactKey;

    /**
     * Artifact 值
     */
    private String artifactValue;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
