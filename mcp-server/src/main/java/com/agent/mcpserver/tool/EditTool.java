package com.agent.mcpserver.tool;

import com.agent.mcpserver.context.SessionContext;
import com.agent.mcpserver.tool.support.EditReplacer;
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
 * Edit 工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditTool {

    private final WorkspaceToolConfig workspaceConfig;
    private final WorkspacePathSupport workspacePathSupport;
    private final FileReadTracker fileReadTracker;
    private final EditReplacer editReplacer;

    @McpTool(
            name = "edit",
            descriptionResource = "classpath:description/edit.txt"
    )
    public Map<String, Object> edit(
            @JsonProperty(value = "filePath", required = true) String filePath,
            @JsonProperty(value = "oldString", required = true) String oldString,
            @JsonProperty(value = "newString", required = true) String newString,
            @JsonProperty("replaceAll") Boolean replaceAll
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (filePath == null || filePath.isBlank()) {
            result.put("success", false);
            result.put("error", "filePath is required");
            return result;
        }
        if (oldString == null || oldString.isEmpty()) {
            result.put("success", false);
            result.put("error", "oldString is required");
            return result;
        }
        if (newString == null) {
            result.put("success", false);
            result.put("error", "newString is required");
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

        if (!Files.exists(path) || Files.isDirectory(path)) {
            result.put("success", false);
            result.put("error", "File not found: " + workspacePathSupport.relativePath(workspaceConfig, path));
            return result;
        }

        String sessionId = SessionContext.getSessionId();
        if (!fileReadTracker.hasRead(sessionId, path)) {
            result.put("success", false);
            result.put("error", "File has not been read in this session. Read it first.");
            return result;
        }

        boolean safeReplaceAll = replaceAll != null && replaceAll;
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            EditReplacer.ReplaceResult replaceResult = editReplacer.replace(source, oldString, newString, safeReplaceAll);

            if (!replaceResult.isMatched()) {
                result.put("success", false);
                result.put("error", replaceResult.getMessage());
                return result;
            }

            Files.writeString(path, replaceResult.getUpdatedContent(), StandardCharsets.UTF_8);

            result.put("success", true);
            result.put("path", workspacePathSupport.relativePath(workspaceConfig, path));
            result.put("strategy", replaceResult.getStrategy());
            result.put("replacedCount", replaceResult.getReplacedCount());
            return result;
        } catch (Exception e) {
            log.error("[EditTool] edit failed", e);
            result.put("success", false);
            result.put("error", "Edit failed: " + e.getMessage());
            return result;
        }
    }
}
