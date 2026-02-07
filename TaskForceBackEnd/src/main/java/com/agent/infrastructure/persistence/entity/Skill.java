package com.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 实体类
 */
@Data
@TableName("skills")
public class Skill {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Skill 唯一标识（例如：skill_name）
     */
    private String skillId;

    /**
     * Skill 名称
     */
    private String name;

    /**
     * Skill 文件路径
     */
    private String path;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
