package com.agent.mcpserver.service;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolVO;
import com.agent.mcpserver.tool.McpTool;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native 工具扫描器
 * 自动扫描 @Tool 注解的方法，并注册为 native:: 前缀的工具
 */
@Slf4j
@Component
public class NativeToolScanner {

    private static final String NATIVE_PREFIX = "native";
    private static final Set<String> DISABLED_NATIVE_TOOLS = Set.of(
            "read", "write", "edit", "glob", "grep", "bash"
    );
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ApplicationContext applicationContext;
    private final ResourceLoader resourceLoader;

    @Value("${mcp.native.scan-packages:com.agent.mcpserver.tool}")
    private String scanPackages;

    /**
     * 工具方法缓存：globalToolId -> ToolMeta
     */
    private final Map<String, ToolMeta> toolCache = new ConcurrentHashMap<>();

    /**
     * 是否已扫描
     */
    private volatile boolean scanned = false;

    /**
     * 工具方法元数据
     */
    private record ToolMeta(
            Object bean,
            Method method,
            String name,
            String description,
            String[] paramNames
    ) {}

    public NativeToolScanner(ApplicationContext applicationContext, ResourceLoader resourceLoader) {
        this.applicationContext = applicationContext;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 确保已扫描（延迟初始化）
     */
    private void ensureScanned() {
        if (!scanned) {
            synchronized (this) {
                if (!scanned) {
                    scan();
                    scanned = true;
                }
            }
        }
    }

    /**
     * 扫描 @Tool 方法
     */
    private void scan() {
        log.info("[NativeToolScanner] Scanning packages: {}", scanPackages);

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Service.class);
        int scannedBeans = 0;
        int registeredTools = 0;

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> clazz = getTargetClass(bean.getClass());
            String packageName = clazz.getPackage() != null ? clazz.getPackage().getName() : "";

            // 只扫描指定包
            if (!packageName.startsWith(scanPackages)) {
                continue;
            }

            scannedBeans++;

            // 扫描 @Tool / @McpTool 方法
            for (Method method : clazz.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                McpTool mcpTool = method.getAnnotation(McpTool.class);

                if (annotation == null && mcpTool == null) {
                    continue;
                }

                String toolName = annotation != null ? annotation.name() : mcpTool.name();
                if (toolName != null && DISABLED_NATIVE_TOOLS.contains(toolName)) {
                    log.info("[NativeToolScanner] Skip disabled native tool: {}", toolName);
                    continue;
                }
                String globalId = NATIVE_PREFIX + "::" + toolName;
                String description = resolveDescription(annotation, mcpTool);

                // 获取参数名称
                String[] paramNames = getParameterNames(method);

                // 缓存工具元数据
                toolCache.put(globalId, new ToolMeta(
                        bean, method, toolName, description, paramNames
                ));

                registeredTools++;
                log.info("[NativeToolScanner] Registered: {} from {}.{}",
                        globalId, clazz.getSimpleName(), method.getName());
            }
        }

        log.info("[NativeToolScanner] Scanned {} beans, registered {} native tools",
                scannedBeans, registeredTools);
    }

    /**
     * 列出所有 Native 工具
     */
    public List<ToolVO> listTools() {
        ensureScanned();
        return toolCache.entrySet().stream()
                .map(entry -> {
                    String globalId = entry.getKey();
                    ToolMeta meta = entry.getValue();

                    return ToolVO.builder()
                            .name(globalId)
                            .description(meta.description())
                            .inputSchema(buildInputSchema(meta.method()))
                            .sourceType("NATIVE")
                            .providerId(NATIVE_PREFIX)
                            .build();
                })
                .toList();
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String toolId) {
        ensureScanned();
        return toolCache.containsKey(toolId);
    }

    /**
     * 调用 Native 工具
     */
    public ToolCallResult callTool(String toolId, Map<String, Object> args) {
        ensureScanned();
        ToolMeta meta = toolCache.get(toolId);
        if (meta == null) {
            return ToolCallResult.error("Tool not found: " + toolId);
        }

        try {
            // 准备调用参数
            Object[] params = prepareArguments(meta, args);

            // 调用方法
            meta.method().setAccessible(true);
            Object result = meta.method().invoke(meta.bean(), params);

            // 转换结果
            if (result == null) {
                return ToolCallResult.text("null");
            }

            String resultJson = objectMapper.writeValueAsString(result);
            return ToolCallResult.text(resultJson);

        } catch (Exception e) {
            log.error("[NativeToolScanner] Tool call failed: {}", toolId, e);
            return ToolCallResult.error("Tool call failed: " + e.getMessage());
        }
    }

    /**
     * 获取目标类（处理 CGLIB 代理）
     */
    private Class<?> getTargetClass(Class<?> clazz) {
        if (clazz.getName().contains("$$EnhancerBySpringCGLIB$$")) {
            return clazz.getSuperclass();
        }
        return clazz;
    }

    /**
     * 构建方法参数的 JSON Schema
     */
    private Object buildInputSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : method.getParameters()) {
            String paramName = param.getName();
            Class<?> paramType = param.getType();

            Map<String, Object> paramSchema = new LinkedHashMap<>();
            paramSchema.put("type", getJsonType(paramType));

            // 检查 @JsonProperty 注解
            JsonProperty jsonProperty = param.getAnnotation(JsonProperty.class);
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
            JsonProperty jsonProperty = param.getAnnotation(JsonProperty.class);
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
        if (type.isArray() || Collection.class.isAssignableFrom(type)) return "array";
        return "object";
    }

    /**
     * 准备方法调用参数
     */
    private Object[] prepareArguments(ToolMeta meta, Map<String, Object> arguments) {
        Parameter[] params = meta.method().getParameters();
        String[] paramNames = meta.paramNames();
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

    private String resolveDescription(Tool tool, McpTool mcpTool) {
        if (mcpTool != null) {
            if (mcpTool.description() != null && !mcpTool.description().isBlank()) {
                return mcpTool.description();
            }
            if (mcpTool.descriptionResource() != null && !mcpTool.descriptionResource().isBlank()) {
                String loaded = readResource(mcpTool.descriptionResource());
                if (!loaded.isBlank()) {
                    return loaded;
                }
            }
        }
        if (tool != null && tool.description() != null && !tool.description().isBlank()) {
            return tool.description();
        }
        return "";
    }

    private String readResource(String location) {
        try {
            String normalizedLocation = location.startsWith("classpath:")
                    ? location
                    : "classpath:" + location;
            Resource resource = resourceLoader.getResource(normalizedLocation);
            if (!resource.exists()) {
                log.warn("[NativeToolScanner] Description resource not found: {}", normalizedLocation);
                return "";
            }
            try (InputStream inputStream = resource.getInputStream()) {
                String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                return text
                        .replace("${today}", LocalDate.now().toString())
                        .replace("{{date}}", LocalDate.now().toString())
                        .trim();
            }
        } catch (Exception e) {
            log.warn("[NativeToolScanner] Failed to read description resource: {}", location, e);
            return "";
        }
    }
}
