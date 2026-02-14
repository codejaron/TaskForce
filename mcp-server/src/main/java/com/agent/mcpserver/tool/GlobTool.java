package com.agent.mcpserver.tool;

import com.agent.mcpserver.tool.support.WorkspacePathSupport;
import com.agent.mcpserver.tool.support.WorkspaceToolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Glob 工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobTool {

    private static final int MAX_RESULTS = 100;

    private final WorkspaceToolConfig workspaceConfig;
    private final WorkspacePathSupport workspacePathSupport;

    @McpTool(
            name = "glob",
            descriptionResource = "classpath:description/glob.txt"
    )
    public Map<String, Object> glob(
            @JsonProperty(value = "pattern", required = true) String pattern,
            @JsonProperty("path") String path
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (pattern == null || pattern.isBlank()) {
            result.put("success", false);
            result.put("error", "pattern is required");
            return result;
        }

        Path searchRoot;
        try {
            searchRoot = workspacePathSupport.resolvePath(workspaceConfig, path);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }

        List<String> command = List.of(
                workspaceConfig.getRipgrepPath(),
                "--files",
                "--glob",
                pattern,
                searchRoot.toString()
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.put("success", false);
                result.put("error", "glob timed out");
                return result;
            }

            List<String> lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                    .lines()
                    .toList();

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                result.put("success", false);
                result.put("error", "glob failed: " + String.join("\n", lines));
                return result;
            }

            List<FileEntry> entries = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                Path absolute = Path.of(line).toAbsolutePath().normalize();
                entries.add(new FileEntry(absolute, fileLastModified(absolute)));
            }

            entries.sort(Comparator.comparingLong(FileEntry::modifiedAt).reversed());
            boolean truncated = entries.size() > MAX_RESULTS;
            List<FileEntry> finalEntries = truncated ? entries.subList(0, MAX_RESULTS) : entries;

            List<String> files = finalEntries.stream()
                    .map(entry -> workspacePathSupport.relativePath(workspaceConfig, entry.path()))
                    .toList();

            result.put("success", true);
            result.put("matches", entries.size());
            result.put("truncated", truncated);
            result.put("files", files);
            return result;
        } catch (IOException e) {
            if (isRipgrepMissing(e)) {
                result.put("success", false);
                result.put("error", "ripgrep not found. Install ripgrep and set workspace.ripgrep-path.");
                return result;
            }
            result.put("success", false);
            result.put("error", "glob failed: " + e.getMessage());
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "glob failed: " + e.getMessage());
            return result;
        }
    }

    private boolean isRipgrepMissing(IOException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Cannot run program") && message.contains(workspaceConfig.getRipgrepPath());
    }

    private long fileLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private record FileEntry(Path path, long modifiedAt) {
    }
}
