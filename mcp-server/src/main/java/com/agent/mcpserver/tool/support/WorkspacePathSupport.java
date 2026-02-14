package com.agent.mcpserver.tool.support;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工作区路径解析工具。
 */
@Component
public class WorkspacePathSupport {

    public Path workspaceRoot(WorkspaceToolConfig config) {
        return Paths.get(config.getBasePath()).toAbsolutePath().normalize();
    }

    public Path resolvePath(WorkspaceToolConfig config, String inputPath) {
        Path root = workspaceRoot(config);
        String raw = (inputPath == null || inputPath.isBlank()) ? "." : inputPath;
        Path path = Paths.get(raw);
        Path resolved = path.isAbsolute() ? path.toAbsolutePath().normalize() : root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace: " + inputPath);
        }
        return resolved;
    }

    public String relativePath(WorkspaceToolConfig config, Path path) {
        Path root = workspaceRoot(config);
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root)) {
            return ".";
        }
        return root.relativize(normalized).toString().replace("\\", "/");
    }
}
