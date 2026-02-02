package com.agent.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步任务配置
 * 启用 @Async 注解支持
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    // Spring Boot 会自动使用 application.yml 中的 spring.task.execution 配置
    // 无需手动定义 TaskExecutor Bean
}
