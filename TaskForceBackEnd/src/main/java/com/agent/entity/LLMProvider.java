package com.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LLM 模型渠道实体
 * 存储用户自定义的模型接口配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_providers")
public class LLMProvider {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("name")
    private String name; // 用户自定义名称
    
    @TableField("type")
    private String type; // OPENAI/AZURE/OLLAMA/DEEPSEEK/ZHIPU/CUSTOM
    
    @TableField("base_url")
    private String baseUrl;
    
    @TableField("api_key")
    private String apiKey; // AES加密存储
    
    @TableField("config")
    private String config; // 扩展配置(JSON)
    
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
