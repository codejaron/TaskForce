package com.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 渠道模型子表实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("channel_models")
public class ChannelModel {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("channel_id")
    private Long channelId;

    @TableField("model_value")
    private String modelValue; // API调用时使用的真实模型标识

    @TableField("display_name")
    private String displayName; // 给用户看的名称

    @TableField("created_at")
    private LocalDateTime createdAt;
}

