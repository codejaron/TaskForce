package com.agent.api.controller;

import com.agent.api.response.ApiResponse;
import com.agent.api.dto.ToolCallArtifactDTO;
import com.agent.infrastructure.persistence.entity.ToolCall;
import com.agent.service.ToolCallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用记录控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/tool-calls")
@RequiredArgsConstructor
public class ToolCallController {

    private final ToolCallService toolCallService;

    /**
     * 获取会话的所有工具调用记录
     */
    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<ToolCall>> getBySession(@PathVariable String sessionId) {
        try {
            List<ToolCall> toolCalls = toolCallService.getBySessionId(sessionId);
            return ApiResponse.success(toolCalls);
        } catch (Exception e) {
            log.error("Get tool calls by session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取步骤的工具调用记录
     */
    @GetMapping("/step/{stepId}")
    public ApiResponse<List<ToolCall>> getByStep(@PathVariable String stepId) {
        try {
            List<ToolCall> toolCalls = toolCallService.getByStepId(stepId);
            return ApiResponse.success(toolCalls);
        } catch (Exception e) {
            log.error("Get tool calls by step failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取工具调用产物列表。
     */
    @GetMapping("/{toolCallId}/artifacts")
    public ApiResponse<List<ToolCallArtifactDTO>> getArtifacts(@PathVariable String toolCallId) {
        try {
            ToolCall toolCall = toolCallService.getByToolCallId(toolCallId);
            if (toolCall == null) {
                return ApiResponse.error("Tool call not found: " + toolCallId);
            }

            List<ToolCallArtifactDTO> artifacts = new ArrayList<>();
            String filePath = toolCall.getFilePath();
            if (filePath != null && !filePath.isBlank()) {
                artifacts.add(ToolCallArtifactDTO.builder()
                        .artifactId("file")
                        .name(Path.of(filePath).getFileName().toString())
                        .filePath(filePath)
                        .syncStatus(toolCall.getSyncStatus())
                        .sizeBytes(resolveFileSize(filePath))
                        .downloadPath("/api/tool-calls/" + toolCallId + "/artifacts/file/download")
                        .build());
            } else if (toolCall.getToolResult() != null && !toolCall.getToolResult().isBlank()) {
                artifacts.add(ToolCallArtifactDTO.builder()
                        .artifactId("result")
                        .name("tool_result.txt")
                        .filePath(null)
                        .syncStatus(toolCall.getSyncStatus())
                        .sizeBytes((long) toolCall.getToolResult().getBytes(StandardCharsets.UTF_8).length)
                        .downloadPath("/api/tool-calls/" + toolCallId + "/artifacts/result/download")
                        .build());
            }
            return ApiResponse.success(artifacts);
        } catch (Exception e) {
            log.error("Get tool artifacts failed: toolCallId={}", toolCallId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 下载工具调用产物。
     */
    @GetMapping("/{toolCallId}/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> downloadArtifact(@PathVariable String toolCallId, @PathVariable String artifactId) {
        ToolCall toolCall = toolCallService.getByToolCallId(toolCallId);
        if (toolCall == null) {
            return ResponseEntity.notFound().build();
        }

        String filePath = toolCall.getFilePath();
        if ("file".equals(artifactId) && filePath != null && !filePath.isBlank()) {
            try {
                Path path = Path.of(filePath);
                if (Files.exists(path) && Files.isRegularFile(path)) {
                    byte[] bytes = Files.readAllBytes(path);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + path.getFileName() + "\"")
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(bytes);
                }
            } catch (Exception e) {
                log.warn("Download artifact file failed: toolCallId={}, filePath={}, err={}",
                        toolCallId, filePath, e.getMessage());
            }
        }

        String fallback = toolCall.getToolResult();
        if (fallback == null) {
            fallback = "";
        }
        byte[] bytes = fallback.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tool_result.txt\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(bytes);
    }

    private Long resolveFileSize(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.size(path);
            }
        } catch (Exception e) {
            log.debug("Resolve file size failed: path={}, err={}", filePath, e.getMessage());
        }
        return null;
    }
}
