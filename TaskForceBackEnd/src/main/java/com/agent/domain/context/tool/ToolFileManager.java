package com.agent.domain.context.tool;

import com.agent.domain.context.storage.WorkspaceStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/**
 * 工具结果文件管理器
 * 负责工具调用结果的文件命名和保存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolFileManager {
    
    private final WorkspaceStorage storage;
    
    /**
     * 保存工具结果
     * @param sessionId 会话ID
     * @param stepIndex 步骤索引
     * @param toolName 工具名称
     * @param args 工具参数
     * @param result 工具结果
     * @return 文件相对路径
     */
    public String saveToolResult(String sessionId, int stepIndex,
                                  String toolName, Map<String, Object> args,
                                  String result) {
        // 生成文件名
        String fileName = generateFileName(toolName, args);
        String path = String.format("step_%03d/tools/%s", stepIndex, fileName);
        
        // 保存
        storage.writeFile(sessionId, path, result);
        log.debug("保存工具结果: sessionId={}, path={}", sessionId, path);
        
        return path;
    }
    
    /**
     * 生成文件名：工具名_关键参数.扩展名
     */
    private String generateFileName(String toolName, Map<String, Object> args) {
        String suffix = extractKeySuffix(toolName, args);
        String ext = determineExtension(toolName);
        return toolName + "_" + suffix + ext;
    }
    
    /**
     * 提取关键参数作为文件名后缀
     */
    private String extractKeySuffix(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "search", "web_search" -> sanitize(getStringArg(args, "query", "result"));
            case "browser", "web_fetch" -> extractDomain(getStringArg(args, "url", "page"));
            case "shell", "execute" -> sanitize(truncate(getStringArg(args, "command", "cmd"), 20));
            case "file_read", "read_file" -> extractFileName(getStringArg(args, "path", "file"));
            case "analyze", "review" -> sanitize(getStringArg(args, "target", "result"));
            case "database_query" -> sanitize(truncate(getStringArg(args, "sql", "query"), 30));
            default -> sanitize(getStringArg(args, "name", "result"));
        };
    }
    
    /**
     * 确定文件扩展名
     */
    private String determineExtension(String toolName) {
        // 文本类工具用 .md，其他用 .json
        return switch (toolName) {
            case "browser", "web_fetch", "file_read", "read_file" -> ".md";
            default -> ".json";
        };
    }
    
    /**
     * 清理文件名：只保留字母、数字、中文、下划线、连字符
     */
    private String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "result";
        }
        return s.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_")
                .substring(0, Math.min(s.length(), 30));
    }
    
    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLength) {
        if (s == null || s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength);
    }
    
    /**
     * 从 URL 提取域名
     */
    private String extractDomain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host != null) {
                // 移除 www. 前缀
                return host.replaceFirst("^www\\.", "").replace(".", "_");
            }
        } catch (Exception e) {
            log.debug("解析 URL 失败: {}", url);
        }
        return sanitize(url);
    }
    
    /**
     * 从路径提取文件名
     */
    private String extractFileName(String path) {
        if (path == null || path.isEmpty()) {
            return "file";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            path = path.substring(lastSlash + 1);
        }
        // 移除扩展名
        int lastDot = path.lastIndexOf('.');
        if (lastDot > 0) {
            path = path.substring(0, lastDot);
        }
        return sanitize(path);
    }
    
    /**
     * 安全获取字符串参数
     */
    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }
}
