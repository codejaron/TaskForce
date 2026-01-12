package com.agent.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动扫描并注册 @Tool 注解的方法
 * 在 Spring 容器启动后，扫描所有 Bean 中的 @Tool 方法，并保存元数据
 * 运行时根据 sessionId 动态创建 FunctionCallback
 */
@Slf4j
@Configuration
public class AutoToolConfiguration implements ApplicationContextAware {

    private ApplicationContext applicationContext;
    private final Map<String, ToolMetadata> toolMetadataCache = new ConcurrentHashMap<>();

    /**
     * 工具元数据
     */
    @Data
    private static class ToolMetadata {
        private final Object bean;
        private final Method method;
        private final String name;
        private final String description;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void scanTools() {
        log.info("[AutoToolConfiguration] Starting to scan @Tool methods...");

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        int totalScanned = 0;
        int totalRegistered = 0;

        for (String beanName : beanNames) {
            // 跳过自己，避免循环依赖
            if ("autoToolConfiguration".equals(beanName)) {
                continue;
            }

            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> clazz = bean.getClass();

                // 跳过 Spring 内部类和代理类
                if (clazz.getName().contains("$$") && !clazz.getName().contains("EnhancerBySpringCGLIB")) {
                    continue;
                }

                // 获取实际类（处理 CGLIB 代理）
                Class<?> targetClass = clazz;
                if (clazz.getName().contains("EnhancerBySpringCGLIB")) {
                    targetClass = clazz.getSuperclass();
                }

                totalScanned++;

                // 扫描方法上的 @Tool 注解
                for (Method method : targetClass.getDeclaredMethods()) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (toolAnnotation != null) {
                        String toolName = toolAnnotation.name();
                        String toolDescription = toolAnnotation.description();

                        // 检查重名
                        if (toolMetadataCache.containsKey(toolName)) {
                            log.warn("[AutoToolConfiguration] Duplicate tool name detected: {}. Skipping.", toolName);
                            continue;
                        }

                        // 保存工具元数据（不再创建 Wrapper）
                        ToolMetadata metadata = new ToolMetadata(bean, method, toolName, toolDescription);
                        toolMetadataCache.put(toolName, metadata);
                        totalRegistered++;

                        log.info("[AutoToolConfiguration] Registered @Tool: {} from {}.{}",
                            toolName, targetClass.getSimpleName(), method.getName());
                    }
                }
            } catch (Exception e) {
                log.debug("[AutoToolConfiguration] Failed to scan bean: {}, error: {}",
                    beanName, e.getMessage());
            }
        }

        log.info("[AutoToolConfiguration] Scan completed. Scanned {} beans, registered {} @Tool methods.",
            totalScanned, totalRegistered);

        if (totalRegistered > 0) {
            log.info("[AutoToolConfiguration] Available tools: {}", toolMetadataCache.keySet());
        }
    }

    /**
     * 获取所有注册的工具回调（动态创建，传入 sessionId）
     * @param sessionId 会话ID，用于跨线程传递上下文
     */
    public FunctionCallback[] getToolCallbacks(String sessionId) {
        return toolMetadataCache.values().stream()
            .map(metadata -> new MethodToolCallbackWrapper(
                metadata.bean,
                metadata.method,
                metadata.name,
                metadata.description,
                sessionId  // 传入 sessionId
            ))
            .toArray(FunctionCallback[]::new);
    }

    /**
     * 获取所有注册的工具回调（向后兼容，不传 sessionId）
     */
    public FunctionCallback[] getToolCallbacks() {
        return getToolCallbacks(null);
    }

    /**
     * 获取指定名称的工具回调
     */
    public FunctionCallback getToolCallback(String name, String sessionId) {
        ToolMetadata metadata = toolMetadataCache.get(name);
        if (metadata == null) {
            return null;
        }
        return new MethodToolCallbackWrapper(
            metadata.bean,
            metadata.method,
            metadata.name,
            metadata.description,
            sessionId
        );
    }

    /**
     * 获取指定名称的工具回调（向后兼容）
     */
    public FunctionCallback getToolCallback(String name) {
        return getToolCallback(name, null);
    }

    /**
     * 获取所有工具名称
     */
    public String[] getToolNames() {
        return toolMetadataCache.keySet().toArray(new String[0]);
    }
}
