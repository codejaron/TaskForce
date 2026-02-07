package com.agent.domain.context.storage;

import com.agent.infrastructure.mcp.RemoteMcpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作空间存储（基于 MCP Filesystem）
 * 通过 RemoteMcpClient 调用 filesystem 工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceStorage {
    
    private final RemoteMcpClient mcpClient;
    
    @Value("${context.workspace.base-path:/workspace}")
    private String basePath;
    
    /**
     * 写入文件
     */
    public void writeFile(String sessionId, String relativePath, String content) {
        String fullPath = getFullPath(sessionId, relativePath);
        try {
            // 1. 先创建父目录
            String parentDir = getParentDirectory(fullPath);
            if (parentDir != null && !parentDir.equals(getWorkspacePath(sessionId))) {
                createDirectoryRecursive(parentDir);
            }
            
            // 2. 写入文件
            Map<String, Object> args = new HashMap<>();
            args.put("path", fullPath);
            args.put("content", content);
            
            // 调用 RemoteMcpClient，返回 ToolCallResultDTO
            RemoteMcpClient.ToolCallResultDTO result = mcpClient.callTool("filesystem::write_file", args);
            
            if (result.isError()) {
                throw new RuntimeException("MCP 调用失败: " + result.getTextContent());
            }
            
            log.debug("写入文件: {}", fullPath);
        } catch (Exception e) {
            log.error("写入文件失败: {}", fullPath, e);
            throw new RuntimeException("写入文件失败: " + relativePath, e);
        }
    }
    
    /**
     * 递归创建目录
     */
    private void createDirectoryRecursive(String fullPath) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", fullPath);

            RemoteMcpClient.ToolCallResultDTO result = mcpClient.callTool("filesystem::create_directory", args);

            if (result.isError()) {
                String errorMsg = result.getTextContent();
                // 如果是父目录不存在的错误，递归创建父目录
                if (errorMsg != null && errorMsg.contains("Parent directory does not exist")) {
                    String parentDir = getParentDirectory(fullPath);
                    if (parentDir != null) {
                        createDirectoryRecursive(parentDir);
                        // 再次尝试创建当前目录
                        result = mcpClient.callTool("filesystem::create_directory", args);
                        if (result.isError()) {
                            log.warn("创建目录失败: {}, error: {}", fullPath, result.getTextContent());
                        }
                    }
                } else if (!errorMsg.contains("already exists")) {
                    log.warn("创建目录失败: {}, error: {}", fullPath, errorMsg);
                }
            } else {
                log.debug("创建目录: {}", fullPath);
            }
        } catch (Exception e) {
            log.error("创建目录失败: {}", fullPath, e);
        }
    }

    /**
     * 获取父目录路径
     */
    private String getParentDirectory(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            return path.substring(0, lastSlash);
        }
        return null;
    }
    
    /**
     * 读取文件
     */
    public String readFile(String sessionId, String relativePath) {
        String fullPath = getFullPath(sessionId, relativePath);
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", fullPath);
            
            RemoteMcpClient.ToolCallResultDTO result = mcpClient.callTool("filesystem::read_file", args);
            
            if (result.isError()) {
                throw new RuntimeException("MCP 调用失败: " + result.getTextContent());
            }
            
            return result.getTextContent();
        } catch (Exception e) {
            log.error("读取文件失败: {}", fullPath, e);
            throw new RuntimeException("读取文件失败: " + relativePath, e);
        }
    }

    /**
     * 检查文件是否存在
     */
    public boolean exists(String sessionId, String relativePath) {
        String fullPath = getFullPath(sessionId, relativePath);
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", fullPath);

            RemoteMcpClient.ToolCallResultDTO result = mcpClient.callTool("filesystem::read_file", args);

            // 如果能读取文件，说明文件存在
            return !result.isError();
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * 创建目录
     */
    public void createDirectory(String sessionId) {
        String fullPath = getWorkspacePath(sessionId);
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", fullPath);
            
            RemoteMcpClient.ToolCallResultDTO result = mcpClient.callTool("filesystem::create_directory", args);
            
            if (result.isError()) {
                log.warn("创建目录失败: {}, error: {}", fullPath, result.getTextContent());
            } else {
                log.debug("创建目录: {}", fullPath);
            }
        } catch (Exception e) {
            log.error("创建目录失败: {}", fullPath, e);
            // 不抛出异常，可能目录已存在
        }
    }
    
    /**
     * 检查目录是否存在
     */
    public boolean directoryExists(String sessionId) {
        String fullPath = getWorkspacePath(sessionId);
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", fullPath);
            
            RemoteMcpClient.ToolCallResultDTO result = mcpClient.callTool("filesystem::list_directory", args);
            
            return !result.isError();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 列出目录下的文件
     */
    public List<String> listFiles(String sessionId, String relativePath) {
        String fullPath = getFullPath(sessionId, relativePath);
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", fullPath);
            
            RemoteMcpClient.ToolCallResultDTO result = mcpClient.callTool("filesystem::list_directory", args);
            
            if (result.isError()) {
                log.warn("列出文件失败: {}, error: {}", fullPath, result.getTextContent());
                return new ArrayList<>();
            }
            
            return parseDirectoryListing(result.getTextContent());
        } catch (Exception e) {
            log.error("列出文件失败: {}", fullPath, e);
            return new ArrayList<>();
        }
    }

    
    /**
     * 获取会话工作空间根路径
     */
    public String getWorkspacePath(String sessionId) {
        return basePath + "/" + sessionId;
    }
    
    /**
     * 获取完整路径
     */
    private String getFullPath(String sessionId, String relativePath) {
        return basePath + "/" + sessionId + "/" + relativePath;
    }
    
    /**
     * 解析目录列表
     * MCP list_directory 返回格式可能是：
     * - 纯文本，每行一个文件名
     * - JSON 数组
     */
    private List<String> parseDirectoryListing(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 尝试按行分割
        String[] lines = content.split("\n");
        List<String> files = new ArrayList<>();
        
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.equals(".") && !line.equals("..")) {
                // 移除可能的前缀（如 "- " 或 "* "）
                line = line.replaceFirst("^[-*]\\s+", "");
                files.add(line);
            }
        }
        
        return files;
    }
    public void ensureDirectory(String sessionId, String relativePath) {
        String fullPath = getFullPath(sessionId, relativePath);
        createDirectoryRecursive(fullPath);
    }

}
