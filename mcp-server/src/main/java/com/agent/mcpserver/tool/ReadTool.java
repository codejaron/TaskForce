package com.agent.mcpserver.tool;

import com.agent.mcpserver.context.SessionContext;
import com.agent.mcpserver.tool.support.FileReadTracker;
import com.agent.mcpserver.tool.support.OutputTruncator;
import com.agent.mcpserver.tool.support.WorkspacePathSupport;
import com.agent.mcpserver.tool.support.WorkspaceToolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read 工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadTool {

    private static final int BINARY_SAMPLE_BYTES = 4096;

    private final WorkspaceToolConfig workspaceConfig;
    private final WorkspacePathSupport workspacePathSupport;
    private final FileReadTracker fileReadTracker;
    private final OutputTruncator outputTruncator;

    @McpTool(
            name = "read",
            descriptionResource = "classpath:description/read.txt"
    )
    public Map<String, Object> read(
            @JsonProperty(value = "filePath", required = true) String filePath,
            @JsonProperty("offset") Integer offset,
            @JsonProperty("limit") Integer limit
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (filePath == null || filePath.isBlank()) {
            result.put("success", false);
            result.put("error", "filePath is required");
            return result;
        }
        if (offset != null && offset < 1) {
            result.put("success", false);
            result.put("error", "offset must be greater than or equal to 1");
            return result;
        }
        if (limit != null && limit <= 0) {
            result.put("success", false);
            result.put("error", "limit must be greater than 0");
            return result;
        }

        int safeOffset = offset == null ? 1 : offset;
        int safeLimit = limit == null ? workspaceConfig.getReadDefaultLimit() : limit;

        Path target;
        try {
            target = workspacePathSupport.resolvePath(workspaceConfig, filePath);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }

        if (!Files.exists(target)) {
            result.put("success", false);
            result.put("error", "File not found: " + workspacePathSupport.relativePath(workspaceConfig, target));
            List<String> suggestions = suggestNearby(target);
            if (!suggestions.isEmpty()) {
                result.put("suggestion", "Did you mean one of these?\n" + String.join("\n", suggestions));
            }
            return result;
        }

        try {
            if (Files.isDirectory(target)) {
                return readDirectory(target, safeOffset, safeLimit);
            }

            if (isBinaryFile(target)) {
                result.put("success", false);
                result.put("error", "Cannot read binary file: " + workspacePathSupport.relativePath(workspaceConfig, target));
                return result;
            }

            List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
            int fromIndex = safeOffset - 1;
            if (fromIndex >= lines.size()) {
                result.put("success", false);
                result.put("error", "Offset " + safeOffset + " is out of range for this file (" + lines.size() + " lines)");
                return result;
            }

            int toIndex = Math.min(fromIndex + safeLimit, lines.size());
            List<String> display = new ArrayList<>();
            for (int i = fromIndex; i < toIndex; i++) {
                String line = lines.get(i);
                if (line.length() > workspaceConfig.getReadMaxLineLength()) {
                    line = line.substring(0, workspaceConfig.getReadMaxLineLength()) + "...";
                }
                display.add((i + 1) + ": " + line);
            }

            String content = String.join("\n", display);
            boolean hasMore = toIndex < lines.size();
            content = outputTruncator.truncate(
                    content,
                    workspaceConfig.getMaxOutputLines(),
                    workspaceConfig.getMaxOutputBytes(),
                    "(Output truncated. Use Read with offset/limit to read specific sections.)"
            );

            String sessionId = SessionContext.getSessionId();
            fileReadTracker.markRead(sessionId, target);

            result.put("success", true);
            result.put("type", "file");
            result.put("path", workspacePathSupport.relativePath(workspaceConfig, target));
            result.put("offset", safeOffset);
            result.put("limit", safeLimit);
            result.put("totalLines", lines.size());
            result.put("hasMore", hasMore);
            result.put("content", content);
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Read failed: " + e.getMessage());
            return result;
        }
    }

    private Map<String, Object> readDirectory(Path path, int offset, int limit) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();

        List<String> entries;
        try (var stream = Files.list(path)) {
            entries = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(child -> {
                        String name = child.getFileName().toString();
                        return Files.isDirectory(child) ? name + "/" : name;
                    })
                    .toList();
        }

        int from = Math.max(0, offset - 1);
        int to = Math.min(from + limit, entries.size());
        List<String> sliced = from >= entries.size() ? List.of() : entries.subList(from, to);

        result.put("success", true);
        result.put("type", "directory");
        result.put("path", workspacePathSupport.relativePath(workspaceConfig, path));
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("totalEntries", entries.size());
        result.put("hasMore", to < entries.size());
        result.put("entries", sliced);
        return result;
    }

    private boolean isBinaryFile(Path path) throws Exception {
        String contentType = Files.probeContentType(path);
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("text/")) {
                return false;
            }
            if (normalized.startsWith("image/")
                    || normalized.startsWith("audio/")
                    || normalized.startsWith("video/")
                    || normalized.equals("application/pdf")
                    || normalized.equals("application/zip")
                    || normalized.equals("application/octet-stream")) {
                return true;
            }
        }

        byte[] sample;
        try (InputStream input = Files.newInputStream(path)) {
            sample = input.readNBytes(BINARY_SAMPLE_BYTES);
        }

        if (sample.length == 0) {
            return false;
        }

        int nonPrintable = 0;
        for (byte b : sample) {
            int value = b & 0xFF;
            if (value == 0) {
                return true;
            }
            if ((value < 32 && value != 9 && value != 10 && value != 13) || value == 127) {
                nonPrintable++;
            }
        }

        return ((double) nonPrintable / sample.length) > 0.3;
    }

    private List<String> suggestNearby(Path targetPath) {
        Path parent = targetPath.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }

        String base = targetPath.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            try (var stream = Files.list(parent)) {
                return stream
                        .map(path -> path.getFileName().toString())
                        .filter(name -> {
                            String lower = name.toLowerCase(Locale.ROOT);
                            return lower.contains(base) || base.contains(lower);
                        })
                        .limit(3)
                        .map(name -> parent.resolve(name).toString())
                        .toList();
            }
        } catch (Exception ignore) {
            return List.of();
        }
    }
}
