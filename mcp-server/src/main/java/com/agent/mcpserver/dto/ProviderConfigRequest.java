package com.agent.mcpserver.dto;

import com.agent.mcpserver.entity.ToolProviderConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 提供者配置请求 DTO
 * 用于接收前端发送的配置数据
 */
@Data
public class ProviderConfigRequest {
    
    private String name;
    private String type;  // "STDIO" / "REMOTE_SSE" / "SSE" / "STREAMABLE_HTTP"
    private Boolean enabled;
    private String description;
    
    // STDIO 配置
    private String command;
    private List<String> args;  // 前端发送数组
    private Map<String, String> env;  // 前端发送对象
    
    // SSE 配置
    private String sseUrl;
    private String httpUrl;
    private Map<String, String> headers;  // 前端发送对象
    private Integer timeout;
    
    /**
     * 转换为实体类
     */
    public ToolProviderConfig toEntity(ObjectMapper objectMapper) throws JsonProcessingException {
        ToolProviderConfig.ToolProviderConfigBuilder builder = ToolProviderConfig.builder()
                .name(this.name)
                .enabled(this.enabled != null ? this.enabled : true)
                .description(this.description);
        
        // 处理类型（兼容 "SSE" 和 "REMOTE_SSE"）
        ToolProviderConfig.ProviderType providerType = resolveProviderType(this.type);
        builder.type(providerType);
        
        // STDIO 配置
        if (providerType == ToolProviderConfig.ProviderType.STDIO) {
            builder.command(this.command);
            
            // 将数组转为 JSON 字符串
            if (this.args != null && !this.args.isEmpty()) {
                builder.args(objectMapper.writeValueAsString(this.args));
            }
            
            // 将对象转为 JSON 字符串
            if (this.env != null && !this.env.isEmpty()) {
                builder.env(objectMapper.writeValueAsString(this.env));
            }
        }
        
        // SSE 配置
        if (providerType == ToolProviderConfig.ProviderType.REMOTE_SSE) {
            builder.sseUrl(this.sseUrl);
            builder.timeout(this.timeout != null ? this.timeout : 30);
            
            // 将对象转为 JSON 字符串
            if (this.headers != null && !this.headers.isEmpty()) {
                builder.headers(objectMapper.writeValueAsString(this.headers));
            }
        }
        
        // Streamable HTTP 配置
        if (providerType == ToolProviderConfig.ProviderType.STREAMABLE_HTTP) {
            builder.httpUrl(this.httpUrl != null ? this.httpUrl : this.sseUrl); // 兼容旧字段
            builder.timeout(this.timeout != null ? this.timeout : 30);
        }

        return builder.build();
    }

    private ToolProviderConfig.ProviderType resolveProviderType(String type) {
        if (type == null || type.isBlank()) {
            return ToolProviderConfig.ProviderType.STDIO;
        }
        if ("SSE".equalsIgnoreCase(type) || "REMOTE_SSE".equalsIgnoreCase(type)) {
            return ToolProviderConfig.ProviderType.REMOTE_SSE;
        }
        if ("STREAMABLE_HTTP".equalsIgnoreCase(type) || "HTTP".equalsIgnoreCase(type)) {
            return ToolProviderConfig.ProviderType.STREAMABLE_HTTP;
        }
        return ToolProviderConfig.ProviderType.STDIO;
    }
}
