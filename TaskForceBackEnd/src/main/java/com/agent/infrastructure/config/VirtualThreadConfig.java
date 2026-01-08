package com.agent.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * 虚拟线程配置
 * 使用 Java 21 虚拟线程执行后台任务
 */
@Configuration
@EnableAsync
public class VirtualThreadConfig {

    /**
     * 虚拟线程执行器
     * 用于执行后台工作流任务
     */
    @Bean("virtualThreadExecutor")
    public TaskExecutor virtualThreadExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
