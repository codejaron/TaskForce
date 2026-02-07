package com.agent.infrastructure.sandbox;

import com.alibaba.cloud.ai.sandbox.RuntimeFunctionToolCallback;
import com.alibaba.cloud.ai.sandbox.tools.base.SaaBasePythonRunner;
import com.alibaba.cloud.ai.sandbox.tools.base.SaaBaseShellRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sandbox 工具配置类
 * 提供 Python 和 Shell 执行器
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "sandbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SandboxConfig {

    /**
     * 创建 Python 执行器工具
     */
    @Bean
    public SaaPythonExecutor saaPythonExecutor() {
        log.info("Creating SaaPythonExecutor");
        return new SaaPythonExecutor();
    }

    /**
     * 创建 Shell 执行器工具
     */
    @Bean
    public SaaShellExecutor saaShellExecutor() {
        log.info("Creating SaaShellExecutor");
        return new SaaShellExecutor();
    }

    /**
     * 创建 Sandbox 工具列表
     * 返回所有 Sandbox 工具的 ToolCallback 列表
     */
    @Bean
    public List<ToolCallback> sandboxTools(
            SaaPythonExecutor pythonExecutor,
            SaaShellExecutor shellExecutor) {

        List<ToolCallback> tools = new ArrayList<>();

        // 添加 Python 执行器
        RuntimeFunctionToolCallback<?, ?> pythonTool = pythonExecutor.buildTool();
        tools.add(pythonTool);
        log.info("Added Python executor tool: {}", pythonTool.getToolDefinition().name());

        // 添加 Shell 执行器
        RuntimeFunctionToolCallback<?, ?> shellTool = shellExecutor.buildTool();
        tools.add(shellTool);
        log.info("Added Shell executor tool: {}", shellTool.getToolDefinition().name());

        log.info("Created {} sandbox tools", tools.size());
        return tools;
    }
}
