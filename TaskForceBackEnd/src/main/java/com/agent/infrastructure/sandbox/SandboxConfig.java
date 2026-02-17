package com.agent.infrastructure.sandbox;

import com.alibaba.cloud.ai.sandbox.RuntimeFunctionToolCallback;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsAllowedDirectoriesLister;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsDirectoryCreator;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsDirectoryLister;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsFileEditor;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsFileInfoRetriever;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsFileMover;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsFileReader;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsFileSearcher;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsFileWriter;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsMultiFileReader;
import com.alibaba.cloud.ai.sandbox.tools.fs.SaaFsTreeBuilder;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.box.FilesystemSandbox;
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
    private BaseSandbox baseSandbox;
    private FilesystemSandbox filesystemSandbox;

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
        this.baseSandbox = new BaseSandbox(sandboxService, "system", "global");
        log.info("[Sandbox] BaseSandbox created");
        return this.baseSandbox;
    }

    @Bean
    public FilesystemSandbox filesystemSandbox(SandboxService sandboxService) {
        log.info("[Sandbox] Creating FilesystemSandbox (Docker container will start on first use)...");
        this.filesystemSandbox = new FilesystemSandbox(sandboxService, "system", "global");
        log.info("[Sandbox] FilesystemSandbox created");
        return this.filesystemSandbox;
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
    public SaaFsFileReader saaFsFileReader(FilesystemSandbox filesystemSandbox) {
        SaaFsFileReader tool = new SaaFsFileReader();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsFileWriter saaFsFileWriter(FilesystemSandbox filesystemSandbox) {
        SaaFsFileWriter tool = new SaaFsFileWriter();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsFileEditor saaFsFileEditor(FilesystemSandbox filesystemSandbox) {
        SaaFsFileEditor tool = new SaaFsFileEditor();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsDirectoryCreator saaFsDirectoryCreator(FilesystemSandbox filesystemSandbox) {
        SaaFsDirectoryCreator tool = new SaaFsDirectoryCreator();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsDirectoryLister saaFsDirectoryLister(FilesystemSandbox filesystemSandbox) {
        SaaFsDirectoryLister tool = new SaaFsDirectoryLister();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsTreeBuilder saaFsTreeBuilder(FilesystemSandbox filesystemSandbox) {
        SaaFsTreeBuilder tool = new SaaFsTreeBuilder();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsFileMover saaFsFileMover(FilesystemSandbox filesystemSandbox) {
        SaaFsFileMover tool = new SaaFsFileMover();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsFileSearcher saaFsFileSearcher(FilesystemSandbox filesystemSandbox) {
        SaaFsFileSearcher tool = new SaaFsFileSearcher();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsFileInfoRetriever saaFsFileInfoRetriever(FilesystemSandbox filesystemSandbox) {
        SaaFsFileInfoRetriever tool = new SaaFsFileInfoRetriever();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsMultiFileReader saaFsMultiFileReader(FilesystemSandbox filesystemSandbox) {
        SaaFsMultiFileReader tool = new SaaFsMultiFileReader();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public SaaFsAllowedDirectoriesLister saaFsAllowedDirectoriesLister(FilesystemSandbox filesystemSandbox) {
        SaaFsAllowedDirectoriesLister tool = new SaaFsAllowedDirectoriesLister();
        tool.setSandbox(filesystemSandbox);
        return tool;
    }

    @Bean
    public List<ToolCallback> sandboxTools(
            SaaPythonExecutor pythonExecutor,
            SaaShellExecutor shellExecutor,
            SaaFsFileReader fsFileReader,
            SaaFsFileWriter fsFileWriter,
            SaaFsFileEditor fsFileEditor,
            SaaFsDirectoryCreator fsDirectoryCreator,
            SaaFsDirectoryLister fsDirectoryLister,
            SaaFsTreeBuilder fsTreeBuilder,
            SaaFsFileMover fsFileMover,
            SaaFsFileSearcher fsFileSearcher,
            SaaFsFileInfoRetriever fsFileInfoRetriever,
            SaaFsMultiFileReader fsMultiFileReader,
            SaaFsAllowedDirectoriesLister fsAllowedDirectoriesLister) {

        List<ToolCallback> tools = new ArrayList<>();

        RuntimeFunctionToolCallback<?, ?> shellTool = shellExecutor.buildTool();
        tools.add(shellTool);
        log.info("[Sandbox] Added tool: {}", shellTool.getToolDefinition().name());

        RuntimeFunctionToolCallback<?, ?> pythonTool = pythonExecutor.buildTool();
        tools.add(pythonTool);
        log.info("[Sandbox] Added tool: {}", pythonTool.getToolDefinition().name());

        addFsTool(tools, fsFileReader.buildTool());
        addFsTool(tools, fsFileWriter.buildTool());
        addFsTool(tools, fsFileEditor.buildTool());
        addFsTool(tools, fsDirectoryCreator.buildTool());
        addFsTool(tools, fsDirectoryLister.buildTool());
        addFsTool(tools, fsTreeBuilder.buildTool());
        addFsTool(tools, fsFileMover.buildTool());
        addFsTool(tools, fsFileSearcher.buildTool());
        addFsTool(tools, fsFileInfoRetriever.buildTool());
        addFsTool(tools, fsMultiFileReader.buildTool());
        addFsTool(tools, fsAllowedDirectoriesLister.buildTool());

        log.info("[Sandbox] Total sandbox tools: {}", tools.size());
        return tools;
    }

    private void addFsTool(List<ToolCallback> tools, RuntimeFunctionToolCallback<?, ?> callback) {
        tools.add(callback);
        log.info("[Sandbox] Added tool: {}", callback.getToolDefinition().name());
    }

    @PreDestroy
    public void cleanup() {
        log.info("[Sandbox] Shutting down...");
        try {
            if (filesystemSandbox != null && !filesystemSandbox.isClosed()) {
                filesystemSandbox.close();
                log.info("[Sandbox] FilesystemSandbox closed (Docker container stopped)");
            }
        } catch (Exception e) {
            log.warn("[Sandbox] Error closing FilesystemSandbox: {}", e.getMessage());
        }
        try {
            if (baseSandbox != null && !baseSandbox.isClosed()) {
                baseSandbox.close();
                log.info("[Sandbox] BaseSandbox closed (Docker container stopped)");
            }
        } catch (Exception e) {
            log.warn("[Sandbox] Error closing BaseSandbox: {}", e.getMessage());
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
