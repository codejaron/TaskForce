package com.agent.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * MCP Server 微服务启动类
 * 统一管理 STDIO、Native Java、Remote SSE 三种工具来源
 */
@SpringBootApplication
@EnableAsync
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
