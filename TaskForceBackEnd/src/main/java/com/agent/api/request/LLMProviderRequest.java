package com.agent.api.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * LLM Provider 创建/更新请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMProviderRequest {
    
    @NotBlank(message = "渠道名称不能为空")
    private String name;
    
    @NotBlank(message = "渠道类型不能为空")
    private String type; // OPENAI/AZURE/OLLAMA/DEEPSEEK/ZHIPU/CUSTOM
    
    @NotBlank(message = "Base URL不能为空")
    private String baseUrl;
    
    private String apiKey; // 前端传过来是明文，后端会加密
    
    private String config; // JSON配置

    // 可选：同时传入该渠道的模型列表（modelValue 为 API 调用值，displayName 为展示名）
    private List<ChannelModelRequest> models;
}
