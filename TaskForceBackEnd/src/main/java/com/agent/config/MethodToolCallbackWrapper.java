package com.agent.config;

import com.agent.infrastructure.context.SessionContextHolder;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * 将 @Tool 注解的方法包装为 Spring AI 的 FunctionCallback
 * 负责参数解析、方法调用和返回值序列化
 */
@Slf4j
public class MethodToolCallbackWrapper implements FunctionCallback {

    private final Object bean;
    private final Method method;
    private final String name;
    private final String description;
    private final String sessionId;  // 新增：用于跨线程传递 sessionId
    private final ObjectMapper objectMapper;

    public MethodToolCallbackWrapper(Object bean, Method method, String name, String description, String sessionId) {
        this.bean = bean;
        this.method = method;
        this.name = name;
        this.description = description;
        this.sessionId = sessionId;
        this.objectMapper = new ObjectMapper();
        this.method.setAccessible(true);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getInputTypeSchema() {
        return generateJsonSchema();
    }

    @Override
    public String call(String functionArguments) {
        // 标记是否设置了 SessionId，用于 finally 块清理
        boolean sessionIdSet = false;

        try {
            // 在工具调用前设置 SessionId 到当前线程（Reactor 线程）
            if (sessionId != null) {
                SessionContextHolder.setSessionId(sessionId);
                sessionIdSet = true;
                log.debug("[ToolCall] SessionId set for tool '{}': {}", name, sessionId);
            }

            log.info("🔧 [ToolCall] Tool='{}' called with arguments: {}", name, functionArguments);

            // 1. 解析 JSON 输入
            JsonNode inputNode = objectMapper.readTree(functionArguments);
            Object[] args = parseArguments(inputNode);

            // 2. 调用方法
            Object result = method.invoke(bean, args);

            // 3. 序列化返回值
            String response = objectMapper.writeValueAsString(result);
            log.info("✅ [ToolCall] Tool='{}' returned: {}", name, response);
            return response;

        } catch (Exception e) {
            log.error("❌ [ToolCall] Tool='{}' failed with error: {}", name, e.getMessage(), e);
            Map<String, Object> errorResponse = Map.of(
                "success", false,
                "error", e.getMessage(),
                "type", e.getClass().getSimpleName()
            );
            try {
                return objectMapper.writeValueAsString(errorResponse);
            } catch (Exception ex) {
                return "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        } finally {
            // 清理 ThreadLocal，避免线程池复用导致的数据污染
            if (sessionIdSet) {
                SessionContextHolder.clear();
                log.debug("[ToolCall] SessionId cleared for tool '{}'", name);
            }
        }
    }

    /**
     * 从 JSON 输入解析方法参数
     */
    private Object[] parseArguments(JsonNode inputNode) throws Exception {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            JsonProperty jsonProp = param.getAnnotation(JsonProperty.class);
            String paramName = jsonProp != null ? jsonProp.value() : param.getName();

            JsonNode valueNode = inputNode.get(paramName);

            if (valueNode == null || valueNode.isNull()) {
                if (jsonProp != null && jsonProp.required()) {
                    throw new IllegalArgumentException("Required parameter missing: " + paramName);
                }
                args[i] = null;
            } else {
                args[i] = objectMapper.treeToValue(valueNode, param.getType());
            }
        }

        return args;
    }

    /**
     * 从方法签名生成 JSON Schema
     */
    private String generateJsonSchema() {
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return "{\"type\": \"object\", \"properties\": {}}";
        }

        StringBuilder schema = new StringBuilder();
        schema.append("{\"type\": \"object\", \"properties\": {");

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            JsonProperty jsonProp = param.getAnnotation(JsonProperty.class);
            String paramName = jsonProp != null ? jsonProp.value() : param.getName();
            String paramType = getJsonType(param.getType());

            schema.append("\"").append(paramName).append("\": {");
            schema.append("\"type\": \"").append(paramType).append("\"");
            schema.append("}");

            if (i < parameters.length - 1) {
                schema.append(", ");
            }
        }

        schema.append("}, \"required\": [");

        // 添加必需参数列表
        boolean first = true;
        for (Parameter param : parameters) {
            JsonProperty jsonProp = param.getAnnotation(JsonProperty.class);
            if (jsonProp != null && jsonProp.required()) {
                String paramName = jsonProp.value();
                if (!first) {
                    schema.append(", ");
                }
                schema.append("\"").append(paramName).append("\"");
                first = false;
            }
        }

        schema.append("]}");

        return schema.toString();
    }

    /**
     * 将 Java 类型映射为 JSON Schema 类型
     */
    private String getJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        } else if (type == int.class || type == Integer.class ||
                   type == long.class || type == Long.class) {
            return "integer";
        } else if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        } else if (type == double.class || type == Double.class ||
                   type == float.class || type == Float.class) {
            return "number";
        } else {
            return "string"; // 默认
        }
    }
}
