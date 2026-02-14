package com.agent.mcpserver.tool;

import com.agent.mcpserver.context.SessionContext;
import com.agent.mcpserver.tool.support.FileReadTracker;
import com.agent.mcpserver.tool.support.WorkspacePathSupport;
import com.agent.mcpserver.tool.support.WorkspaceToolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Write 工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriteTool {

    private final WorkspaceToolConfig workspaceConfig;
    private final WorkspacePathSupport workspacePathSupport;
    private final FileReadTracker fileReadTracker;

    @McpTool(
            name = "write",
            descriptionResource = "classpath:description/write.txt"
    )
    public Map<String, Object> write(
            @JsonProperty(value = "filePath", required = true) String filePath,
            @JsonProperty(value = "content", required = true) String content
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (filePath == null || filePath.isBlank()) {
            result.put("success", false);
            result.put("error", "filePath is required");
            return result;
        }
        if (content == null) {
            result.put("success", false);
            result.put("error", "content is required");
            return result;
        }

        Path path;
        try {
            path = workspacePathSupport.resolvePath(workspaceConfig, filePath);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }

        try {
            String sessionId = SessionContext.getSessionId();
            if (Files.exists(path) && !fileReadTracker.hasRead(sessionId, path)) {
                result.put("success", false);
                result.put("error", "File exists but has not been read in this session. Read it first.");
                return result;
            }

            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);

            result.put("success", true);
            result.put("path", workspacePathSupport.relativePath(workspaceConfig, path));
            result.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Write failed: " + e.getMessage());
            return result;
        }
    }
}
