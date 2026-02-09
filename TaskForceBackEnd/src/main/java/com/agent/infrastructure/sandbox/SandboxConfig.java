package com.agent.infrastructure.sandbox;

import com.alibaba.cloud.ai.sandbox.RuntimeFunctionToolCallback;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.manager.ManagerConfig;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "sandbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SandboxConfig {

    private SandboxService sandboxService;
    private BaseSandbox sandbox;

    @Bean
    public SandboxService sandboxService() {
        log.info("[Sandbox] Creating SandboxService...");
        ManagerConfig config = ManagerConfig.builder().build();
        SandboxService service = new SandboxService(config);
        service.start();
        this.sandboxService = service;
        log.info("[Sandbox] SandboxService started");
        return this.sandboxService;
    }

    @Bean
    public BaseSandbox baseSandbox(SandboxService sandboxService) {
        log.info("[Sandbox] Creating BaseSandbox (Docker container will start on first use)...");
        this.sandbox = new BaseSandbox(sandboxService, "system", "global");
        log.info("[Sandbox] BaseSandbox created");
        return this.sandbox;
    }

    @Bean
    public SaaPythonExecutor saaPythonExecutor(BaseSandbox baseSandbox) {
        SaaPythonExecutor executor = new SaaPythonExecutor();
        executor.setSandbox(baseSandbox);
        log.info("[Sandbox] SaaPythonExecutor created, sandbox injected");
        return executor;
    }

    @Bean
    public SaaShellExecutor saaShellExecutor(BaseSandbox baseSandbox) {
        SaaShellExecutor executor = new SaaShellExecutor();
        executor.setSandbox(baseSandbox);
        log.info("[Sandbox] SaaShellExecutor created, sandbox injected");
        return executor;
    }

    @Bean
    public List<ToolCallback> sandboxTools(
            SaaPythonExecutor pythonExecutor,
            SaaShellExecutor shellExecutor) {

        List<ToolCallback> tools = new ArrayList<>();

        RuntimeFunctionToolCallback<?, ?> pythonTool = pythonExecutor.buildTool();
        tools.add(pythonTool);
        log.info("[Sandbox] Added tool: {}", pythonTool.getToolDefinition().name());

        RuntimeFunctionToolCallback<?, ?> shellTool = shellExecutor.buildTool();
        tools.add(shellTool);
        log.info("[Sandbox] Added tool: {}", shellTool.getToolDefinition().name());

        log.info("[Sandbox] Total sandbox tools: {}", tools.size());
        return tools;
    }

    @PreDestroy
    public void cleanup() {
        log.info("[Sandbox] Shutting down...");
        try {
            if (sandbox != null && !sandbox.isClosed()) {
                sandbox.close();
                log.info("[Sandbox] BaseSandbox closed (Docker container stopped)");
            }
        } catch (Exception e) {
            log.warn("[Sandbox] Error closing sandbox: {}", e.getMessage());
        }
        try {
            if (sandboxService != null) {
                sandboxService.close();
                log.info("[Sandbox] SandboxService closed");
            }
        } catch (Exception e) {
            log.warn("[Sandbox] Error closing SandboxService: {}", e.getMessage());
        }
    }
}
