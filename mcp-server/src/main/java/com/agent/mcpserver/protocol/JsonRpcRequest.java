package com.agent.mcpserver.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * JSON-RPC 2.0 请求格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcRequest {

    @JsonProperty("jsonrpc")
    @Builder.Default
    private String jsonrpc = "2.0";

    /**
     * 请求方法名
     */
    private String method;

    /**
     * 请求参数
     */
    private Map<String, Object> params;

    /**
     * 请求ID（可选，用于关联响应）
     */
    private Object id;
}
