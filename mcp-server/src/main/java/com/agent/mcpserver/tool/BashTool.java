package com.agent.mcpserver.tool;

import com.agent.mcpserver.tool.support.OutputTruncator;
import com.agent.mcpserver.tool.support.WorkspacePathSupport;
import com.agent.mcpserver.tool.support.WorkspaceToolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bash 工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BashTool {

    private static final String TRUNCATION_MARKER = "[...truncated...]";

    private final WorkspaceToolConfig workspaceConfig;
    private final WorkspacePathSupport workspacePathSupport;
    private final OutputTruncator outputTruncator;

    @McpTool(
            name = "bash",
            descriptionResource = "classpath:description/bash.txt"
    )
    public Map<String, Object> bash(
            @JsonProperty(value = "command", required = true) String command,
            @JsonProperty("timeout") Long timeout,
            @JsonProperty("workdir") String workdir,
            @JsonProperty("description") String description
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (command == null || command.isBlank()) {
            result.put("success", false);
            result.put("error", "command is required");
            return result;
        }
        if (timeout != null && timeout <= 0) {
            result.put("success", false);
            result.put("error", "timeout must be a positive number");
            return result;
        }

        long timeoutMs = timeout != null ? timeout : workspaceConfig.getBashDefaultTimeoutMs();
        Path workingPath;
        try {
            workingPath = workspacePathSupport.resolvePath(workspaceConfig, workdir);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }

        List<String> shellCommand = buildShellCommand(command);
        ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
        processBuilder.directory(workingPath.toFile());
        processBuilder.redirectErrorStream(true);

        log.info("[BashTool] command='{}', timeoutMs={}, workdir={}", command, timeoutMs, workingPath);

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        Thread outputReader = null;
        boolean timedOut = false;
        int exitCode = -1;

        try {
            Process process = processBuilder.start();
            outputReader = readStreamAsync(process.getInputStream(), outputBuffer);

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }

            exitCode = process.exitValue();
            if (outputReader != null) {
                outputReader.join(2000);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Failed to execute bash command: " + e.getMessage());
            return result;
        }

        String rawOutput = outputBuffer.toString(StandardCharsets.UTF_8);
        String truncatedOutput = outputTruncator.truncateHeadTail(
                rawOutput,
                workspaceConfig.getMaxOutputLines(),
                workspaceConfig.getMaxOutputBytes(),
                TRUNCATION_MARKER
        );

        result.put("success", !timedOut && exitCode == 0);
        result.put("exitCode", exitCode);
        result.put("timedOut", timedOut);
        result.put("workdir", workspacePathSupport.relativePath(workspaceConfig, workingPath));
        if (description != null && !description.isBlank()) {
            result.put("description", description);
        }
        result.put("output", truncatedOutput);
        return result;
    }

    private List<String> buildShellCommand(String command) {
        if (isWindows()) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("bash", "-lc", command);
    }

    private Thread readStreamAsync(InputStream inputStream, ByteArrayOutputStream outputBuffer) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while ((read = inputStream.read(buffer)) != -1) {
                    synchronized (outputBuffer) {
                        outputBuffer.write(buffer, 0, read);
                    }
                }
            } catch (IOException ignore) {
                // ignore stream read exceptions
            }
        }, "bash-tool-output-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
