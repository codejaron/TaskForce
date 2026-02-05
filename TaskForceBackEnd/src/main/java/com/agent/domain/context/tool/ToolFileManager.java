package com.agent.domain.context.tool;

import com.agent.domain.context.storage.WorkspaceStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final ObjectMapper objectMapper;

    /**
     * 保存工具结果
     *
     * @param sessionId 会话ID
     * @param stepIndex 步骤索引
     * @param toolName  工具名称
     * @param args      工具参数
     * @param result    工具结果
     * @return 文件相对路径
     */
    public String saveToolResult(String sessionId, int stepIndex,
                                 String toolName, Map<String, Object> args,
                                 String result) {
        // 1. 简化工具名
        String shortName = extractShortName(toolName);

        // 2. 提取第一个参数值作为提示
        String hint = extractHint(args);

        // 3. 生成基础文件名
        String baseName = shortName + "_" + hint;

        // 4. 先确保目录存在
        String toolsDir = String.format("step_%03d/tools", stepIndex);
        storage.ensureDirectory(sessionId, toolsDir);

        // 5. 再检查唯一文件名
        String fileName = getUniqueFileName(sessionId, toolsDir, baseName);

        // 6. 保存
        String path = toolsDir + "/" + fileName + ".md";
        String markdown = formatAsMarkdown(toolName, args, result);
        storage.writeFile(sessionId, path, markdown);

        log.debug("保存工具结果: sessionId={}, path={}", sessionId, path);
        return path;
    }


    /**
     * 简化工具名
     * duckduckgo::search -> search
     * filesystem::read_file -> read_file
     */
    private String extractShortName(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return "tool";
        }
        if (toolName.contains("::")) {
            return toolName.substring(toolName.indexOf("::") + 2);
        }
        return toolName;
    }

    /**
     * 提取第一个参数值作为文件名提示
     */
    private String extractHint(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "result";
        }

        Object firstValue = args.values().iterator().next();
        if (firstValue == null) {
            return "result";
        }

        String hint = firstValue.toString();
        return sanitize(truncate(hint, 30));
    }

    /**
     * 获取唯一文件名（重名则加序号）
     */
    private String getUniqueFileName(String sessionId, String toolsDir, String baseName) {
        String path = toolsDir + "/" + baseName + ".md";

        if (!storage.exists(sessionId, path)) {
            return baseName;
        }

        // 加序号
        for (int i = 2; i <= 99; i++) {
            String newName = baseName + "_" + i;
            path = toolsDir + "/" + newName + ".md";
            if (!storage.exists(sessionId, path)) {
                return newName;
            }
        }

        // 兜底：加时间戳
        return baseName + "_" + System.currentTimeMillis();
    }

    /**
     * 清理文件名：只保留字母、数字、中文、下划线、连字符
     */
    private String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "result";
        }
        String sanitized = s.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_")
                .replaceAll("_+", "_")      // 合并连续下划线
                .replaceAll("^_|_$", "");   // 去除首尾下划线

        if (sanitized.isEmpty()) {
            return "result";
        }
        return sanitized.substring(0, Math.min(sanitized.length(), 30));
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
     * 格式化为 Markdown
     */
    private String formatAsMarkdown(String toolName, Map<String, Object> args, String result) {
        StringBuilder md = new StringBuilder();
        md.append("# Tool: ").append(toolName).append("\n\n");

        md.append("## Arguments\n```json\n");
        try {
            md.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(args));
        } catch (Exception e) {
            md.append(args != null ? args.toString() : "{}");
        }
        md.append("\n```\n\n");

        md.append("## Result\n").append(result != null ? result : "");

        return md.toString();
    }
}
