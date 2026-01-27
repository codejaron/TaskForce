package com.agent.mcpserver.service.provider;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原生 Java 工具提供者
 * 管理通过 @Tool 注解定义的 Java 方法
 */
@Slf4j
public class NativeJavaToolProvider extends AbstractToolProvider {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ApplicationContext applicationContext;
    
    /**
     * 工具方法缓存：toolName -> ToolMethod
     */
    private final Map<String, ToolMethod> toolMethods = new HashMap<>();

    /**
     * 工具方法元数据
     */
    private record ToolMethod(Object bean, Method method, String[] paramNames) {}

    public NativeJavaToolProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    protected void doInitialize(ToolProviderConfig config) throws Exception {
        String beanName = config.getBeanName();
        String className = config.getClassName();

        Object toolBean = null;

        // 优先通过 Bean 名称获取
        if (beanName != null && !beanName.isBlank()) {
            try {
                toolBean = applicationContext.getBean(beanName);
                log.info("[NATIVE] Found bean by name: {}", beanName);
            } catch (Exception e) {
                log.warn("[NATIVE] Bean not found by name: {}", beanName);
            }
        }

        // 通过类名获取
        if (toolBean == null && className != null && !className.isBlank()) {
            try {
                Class<?> clazz = Class.forName(className);
                toolBean = applicationContext.getBean(clazz);
                log.info("[NATIVE] Found bean by class: {}", className);
            } catch (Exception e) {
                log.warn("[NATIVE] Bean not found by class: {}", className);
            }
        }

        if (toolBean == null) {
            throw new IllegalArgumentException("Tool bean not found: beanName=" + beanName + ", className=" + className);
        }

        // 扫描 @Tool 注解的方法
        scanToolMethods(toolBean);
    }

    /**
     * 扫描 Bean 中的 @Tool 方法
     */
    private void scanToolMethods(Object bean) {
        Class<?> clazz = bean.getClass();
        
        // 处理 CGLIB 代理
        if (clazz.getName().contains("$$EnhancerBySpringCGLIB$$")) {
            clazz = clazz.getSuperclass();
        }

        for (Method method : clazz.getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null) {
                String toolName = toolAnnotation.name();
                String description = toolAnnotation.description();

                // 构建输入参数 Schema
                Object inputSchema = buildInputSchema(method);

                // 获取参数名称
                String[] paramNames = getParameterNames(method);

                // 注册工具
                ToolDefinition toolDef = ToolDefinition.builder()
                        .name(toolName)
                        .description(description)
                        .inputSchema(inputSchema)
                        .build();
                registerTool(toolDef);

                // 缓存方法引用
                toolMethods.put(toolName, new ToolMethod(bean, method, paramNames));

                log.info("[NATIVE] Registered @Tool: {} from {}.{}", 
                        toolName, clazz.getSimpleName(), method.getName());
            }
        }
    }

    /**
     * 构建方法参数的 JSON Schema
     */
    private Object buildInputSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();

        for (Parameter param : method.getParameters()) {
            String paramName = param.getName();
            Class<?> paramType = param.getType();

            Map<String, Object> paramSchema = new LinkedHashMap<>();
            paramSchema.put("type", getJsonType(paramType));

            // 检查 @JsonProperty 注解
            com.fasterxml.jackson.annotation.JsonProperty jsonProperty = 
                    param.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (jsonProperty != null) {
                if (!jsonProperty.value().isEmpty()) {
                    paramName = jsonProperty.value();
                }
                if (jsonProperty.required()) {
                    required.add(paramName);
                }
            }

            properties.put(paramName, paramSchema);
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * 获取参数名称
     */
    private String[] getParameterNames(Method method) {
        Parameter[] params = method.getParameters();
        String[] names = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            com.fasterxml.jackson.annotation.JsonProperty jsonProperty = 
                    param.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (jsonProperty != null && !jsonProperty.value().isEmpty()) {
                names[i] = jsonProperty.value();
            } else {
                names[i] = param.getName();
            }
        }
        return names;
    }

    /**
     * Java 类型映射到 JSON Schema 类型
     */
    private String getJsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isArray() || java.util.Collection.class.isAssignableFrom(type)) return "array";
        return "object";
    }

    @Override
    protected void doShutdown() {
        toolMethods.clear();
    }

    @Override
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments, String sessionId) {
        ToolMethod toolMethod = toolMethods.get(toolName);
        if (toolMethod == null) {
            return ToolCallResult.error("Tool not found: " + toolName);
        }

        try {
            // 准备调用参数
            Object[] args = prepareArguments(toolMethod, arguments);

            // 调用方法
            toolMethod.method().setAccessible(true);
            Object result = toolMethod.method().invoke(toolMethod.bean(), args);

            // 转换结果
            if (result == null) {
                return ToolCallResult.text("null");
            }

            String resultJson = objectMapper.writeValueAsString(result);
            return ToolCallResult.text(resultJson);

        } catch (Exception e) {
            log.error("[NATIVE] Tool call failed: {} - {}", toolName, e.getMessage(), e);
            return ToolCallResult.error("Tool call failed: " + e.getMessage());
        }
    }

    /**
     * 准备方法调用参数
     */
    private Object[] prepareArguments(ToolMethod toolMethod, Map<String, Object> arguments) {
        Parameter[] params = toolMethod.method().getParameters();
        String[] paramNames = toolMethod.paramNames();
        Object[] args = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            String paramName = paramNames[i];
            Object value = arguments.get(paramName);
            
            if (value != null) {
                args[i] = convertValue(value, params[i].getType());
            } else {
                args[i] = getDefaultValue(params[i].getType());
            }
        }

        return args;
    }

    /**
     * 转换参数值
     */
    private Object convertValue(Object value, Class<?> targetType) {
        if (targetType.isInstance(value)) {
            return value;
        }
        
        try {
            return objectMapper.convertValue(value, targetType);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 获取默认值
     */
    private Object getDefaultValue(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            if (type == boolean.class) return false;
        }
        return null;
    }
}
