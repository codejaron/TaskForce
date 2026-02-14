package com.agent.mcpserver.tool.support;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪会话内文件读取历史。
 * 用于限制 write/edit 在未 read 前直接改写已有文件。
 */
@Component
public class FileReadTracker {

    private static final String GLOBAL_SESSION = "__global__";

    private final ConcurrentHashMap<String, Set<String>> sessionReadFiles = new ConcurrentHashMap<>();

    public void markRead(String sessionId, Path path) {
        String sessionKey = normalizeSession(sessionId);
        String normalizedPath = normalizePath(path);
        sessionReadFiles.computeIfAbsent(sessionKey, ignored -> ConcurrentHashMap.newKeySet())
                .add(normalizedPath);
    }

    public boolean hasRead(String sessionId, Path path) {
        String sessionKey = normalizeSession(sessionId);
        String normalizedPath = normalizePath(path);
        Set<String> files = sessionReadFiles.get(sessionKey);
        return files != null && files.contains(normalizedPath);
    }

    public void clearSession(String sessionId) {
        sessionReadFiles.remove(normalizeSession(sessionId));
    }

    private String normalizeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return GLOBAL_SESSION;
        }
        return sessionId;
    }

    private String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
