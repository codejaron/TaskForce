package com.agent.mcpserver.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 内置工具注解。
 * 用于在 mcp-server 内注册本地工具。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpTool {

    /**
     * 工具名（不带 native:: 前缀）。
     */
    String name();

    /**
     * 工具描述。若为空且 descriptionResource 非空，则从资源文件加载。
     */
    String description() default "";

    /**
     * 工具描述资源路径（例如: classpath:description/bash.txt）。
     */
    String descriptionResource() default "";
}
