package com.agent.mcpserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具调用请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRequest {

    /**
     * 工具名称
     */
    private String name;

    /**
     * 调用参数
     */
    private Map<String, Object> arguments;

    /**
     * 会话ID（可选，用于上下文传递）
     */
    private String sessionId;
}
