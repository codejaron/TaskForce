package com.agent.mcpserver.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON-RPC 2.0 响应格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {

    @JsonProperty("jsonrpc")
    @Builder.Default
    private String jsonrpc = "2.0";

    /**
     * 请求ID
     */
    private Object id;

    /**
     * 成功结果
     */
    private Object result;

    /**
     * 错误信息
     */
    private JsonRpcError error;

    /**
     * 创建成功响应
     */
    public static JsonRpcResponse success(Object id, Object result) {
        return JsonRpcResponse.builder()
                .id(id)
                .result(result)
                .build();
    }

    /**
     * 创建错误响应
     */
    public static JsonRpcResponse error(Object id, int code, String message) {
        return JsonRpcResponse.builder()
                .id(id)
                .error(JsonRpcError.builder()
                        .code(code)
                        .message(message)
                        .build())
                .build();
    }

    /**
     * 创建错误响应（带数据）
     */
    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        return JsonRpcResponse.builder()
                .id(id)
                .error(JsonRpcError.builder()
                        .code(code)
                        .message(message)
                        .data(data)
                        .build())
                .build();
    }

    // 常用错误码
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
