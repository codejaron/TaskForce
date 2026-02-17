package com.agent.api.controller;

import com.agent.api.response.ApiResponse;
import com.agent.api.request.SessionCreateRequest;
import com.agent.infrastructure.persistence.entity.SessionAgent;
import com.agent.infrastructure.sandbox.SessionSandboxManager;
import com.agent.service.SessionService;
import com.agent.service.SessionStopService;
import com.agent.infrastructure.persistence.entity.Session;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话管理控制器
 * 提供会话的 CRUD 操作和 SSE 流式响应
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SessionController {

    private final SessionService sessionService;
    private final SessionStopService sessionStopService;
    private final com.agent.service.SingleChatService singleChatService;
    @Autowired(required = false)
    private SessionSandboxManager sessionSandboxManager;

    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[a-zA-Z]:/");

    /**
     * 获取所有会话
     */
    @GetMapping
    public ApiResponse<List<Session>> listSessions() {
        try {
            List<Session> sessions = sessionService.getAllSessions();
            return ApiResponse.success(sessions);
        } catch (Exception e) {
            log.error("List sessions failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 创建会话（数据库持久化版本）
     */
    @PostMapping("/create")
    public ApiResponse<Session> createPersistentSession(@Valid @RequestBody SessionCreateRequest request) {
        try {
            Session session = sessionService.createSession(request);
            return ApiResponse.success("会话创建成功", session);
        } catch (Exception e) {
            log.error("Create session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 更新会话状态
     */
    @PatchMapping("/{sessionId}/status")
    public ApiResponse<Session> updateSessionStatus(
            @PathVariable String sessionId,
            @RequestParam String status
    ) {
        try {
            Session session = sessionService.updateSessionStatus(sessionId, status);
            return ApiResponse.success("会话状态更新成功", session);
        } catch (Exception e) {
            log.error("Update session status failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 根据类型查询会话
     */
    @GetMapping("/type/{type}")
    public ApiResponse<List<Session>> getSessionsByType(@PathVariable String type) {
        try {
            List<Session> sessions = sessionService.getSessionsByType(type);
            return ApiResponse.success(sessions);
        } catch (Exception e) {
            log.error("Get sessions by type failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 根据状态查询会话
     */
    @GetMapping("/status/{status}")
    public ApiResponse<List<Session>> getSessionsByStatus(@PathVariable String status) {
        try {
            List<Session> sessions = sessionService.getSessionsByStatus(status);
            return ApiResponse.success(sessions);
        } catch (Exception e) {
            log.error("Get sessions by status failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取会话详情（数据库版本）
     */
    @GetMapping("/{sessionId}/detail")
    public ApiResponse<Session> getSessionDetail(@PathVariable String sessionId) {
        try {
            Session session = sessionService.getSessionById(sessionId);
            return ApiResponse.success(session);
        } catch (Exception e) {
            log.error("Get session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取会话中的智能体
     */
    @GetMapping("/{sessionId}/agents")
    public ApiResponse<List<SessionAgent>> getSessionAgents(@PathVariable String sessionId) {
        try {
            List<SessionAgent> agents = sessionService.getSessionAgents(sessionId);
            return ApiResponse.success(agents);
        } catch (Exception e) {
            log.error("Get session agents failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 添加智能体到会话
     */
    @PostMapping("/{sessionId}/agents/{agentId}")
    public ApiResponse<Void> addAgentToSession(
            @PathVariable String sessionId,
            @PathVariable Long agentId
    ) {
        try {
            sessionService.addAgentToSession(sessionId, agentId);
            return ApiResponse.success("智能体添加成功", null);
        } catch (Exception e) {
            log.error("Add agent to session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        try {
            sessionService.deleteSession(sessionId);
            return ApiResponse.success("会话删除成功", null);
        } catch (Exception e) {
            log.error("Delete session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 停止会话流输出
     * 用于用户主动停止 AI 生成
     */
    @PostMapping("/{sessionId}/stop")
    public ApiResponse<Void> stopSession(@PathVariable String sessionId) {
        try {
            // 标记停止标志并主动取消所有任务
            sessionStopService.markStopAndCancel(sessionId);

            // 状态由 team/single-chat 事件流统一维护，不在这里手动更新

            log.info("Session stopped by user: {}", sessionId);
            return ApiResponse.success("会话已停止", null);
        } catch (Exception e) {
            log.error("Stop session failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 单聊接口（SSE 流式响应）
     * 用于与单个 Agent 进行简单对话，无需多 Agent 编排
     */
    @PostMapping("/{sessionId}/single-chat")
    public Flux<ServerSentEvent<String>> singleChat(
            @PathVariable String sessionId,
            @RequestBody UserInputRequest request) {

        // 验证 Session 类型
        try {
            Session session = sessionService.getSessionById(sessionId);
            if (!"CHAT".equals(session.getType())) {
                return Flux.error(new IllegalArgumentException("Session type must be CHAT"));
            }
        } catch (Exception e) {
            log.error("Failed to validate session type", e);
            return Flux.error(e);
        }

        return singleChatService.chat(sessionId, request.getMessage());
    }

    /**
     * 上传文件（或文件夹展开后的文件列表）到会话 workspace。
     */
    @PostMapping(
            value = "/{sessionId}/workspace/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResponse<WorkspaceUploadResult> uploadWorkspaceFiles(
            @PathVariable String sessionId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "paths", required = false) List<String> paths) {
        try {
            sessionService.getSessionById(sessionId);
            if (sessionSandboxManager == null) {
                return ApiResponse.error("Sandbox is disabled");
            }
            if (files == null || files.length == 0) {
                return ApiResponse.error(400, "No files uploaded");
            }

            int uploadedCount = 0;
            List<String> uploadedPaths = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String rawPath = resolveRawPath(file, paths, i);
                String relativePath = normalizeRelativePath(rawPath, file.getOriginalFilename());
                String workspacePath = "/workspace/" + relativePath;
                try (InputStream in = file.getInputStream()) {
                    sessionSandboxManager.writeBinaryFile(sessionId, workspacePath, in);
                }
                uploadedCount++;
                if (uploadedPaths.size() < 50) {
                    uploadedPaths.add(relativePath);
                }
            }

            WorkspaceUploadResult result = new WorkspaceUploadResult();
            result.setUploadedCount(uploadedCount);
            result.setPaths(uploadedPaths);
            return ApiResponse.success("上传成功", result);
        } catch (IllegalArgumentException e) {
            log.warn("Upload workspace files rejected: sessionId={}, err={}", sessionId, e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Upload workspace files failed: sessionId={}", sessionId, e);
            return ApiResponse.error("上传失败: " + e.getMessage());
        }
    }

    private String resolveRawPath(MultipartFile file, List<String> paths, int index) {
        if (paths != null && index < paths.size()) {
            String candidate = paths.get(index);
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        String originalFilename = file == null ? null : file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.isBlank()) {
            return originalFilename;
        }
        return "uploaded-file-" + index;
    }

    private String normalizeRelativePath(String rawPath, String fallbackName) {
        String candidate = rawPath == null ? "" : rawPath.trim();
        candidate = candidate.replace('\\', '/');
        candidate = DRIVE_PREFIX.matcher(candidate).replaceFirst("");
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        if (candidate.isBlank()) {
            candidate = fallbackName == null ? "uploaded-file" : fallbackName;
        }

        String[] segments = candidate.split("/");
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Invalid upload path segment: ..");
            }
            normalized.add(cleanPathSegment(segment));
        }
        if (normalized.isEmpty()) {
            normalized.add("uploaded-file");
        }
        return String.join("/", normalized);
    }

    private String cleanPathSegment(String segment) {
        String cleaned = segment == null ? "" : segment.trim();
        cleaned = cleaned.replace('\0', '_');
        cleaned = cleaned.replaceAll("[\\r\\n\\t]", "_");
        if (cleaned.isBlank()) {
            return "file";
        }
        return cleaned;
    }

    /**
     * 用户输入请求 DTO
     */
    @Data
    public static class UserInputRequest {
        private String message;
    }

    @Data
    public static class WorkspaceUploadResult {
        private int uploadedCount;
        private List<String> paths;
    }
}
