package com.agent.mcpserver.tool;

import com.agent.mcpserver.context.SessionContext;
import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.service.ToolRouter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 工作空间工具（Native Tool）
 * 提供工作空间的读写能力，让 LLM 能够：
 * 1. 浏览历史步骤数据
 * 2. 读取工具调用结果
 * 3. 写入步骤摘要
 *
 * 内部调用 MCP filesystem 工具
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceTools {

    private final ToolRouter toolRouter;

    @Value("${context.workspace.base-path:/workspace}")
    private String workspaceBasePath;

    // ==================== 写入类工具 ====================
    /**
     * 写步骤输出
     */
    @Tool(
            name = "write_step_output",
            description = "写入当前步骤的执行输出。如：需要传递给后续步骤的关键产物（如代码、搜索结果、草稿）"
    )
    public Map<String, Object> writeStepOutput(
            @JsonProperty(value = "content", required = true) String content
    ) {
        try {
            String sessionId = SessionContext.getSessionId();
            Integer stepIndex = SessionContext.getStepIndex();

            if (sessionId == null || sessionId.isEmpty()) {
                log.error("[WorkspaceTools] writeStepOutput: 无法获取 sessionId");
                return Map.of("success", false, "error", "无法获取当前会话ID");
            }

            if (stepIndex == null || stepIndex <= 0) {
                log.error("[WorkspaceTools] writeStepOutput: 无法获取 stepIndex");
                return Map.of("success", false, "error", "无法获取当前步骤索引");
            }

            log.info("[WorkspaceTools] writeStepOutput: sessionId={}, stepIndex={}, contentLength={}",
                    sessionId, stepIndex, content.length());

            // 构建文件路径
            String path = String.format("%s/%s/step_%03d/output.md",
                    workspaceBasePath, sessionId, stepIndex);

            // 调用 filesystem::write_file
            Map<String, Object> args = new HashMap<>();
            args.put("path", path);
            args.put("content", content);

            ToolCallResult result = toolRouter.callTool("filesystem::write_file", args, sessionId);

            if (result.getIsError()) {
                log.error("[WorkspaceTools] writeStepOutput 写入失败: {}", extractTextContent(result));
                return Map.of("success", false, "error", "写入失败: " + extractTextContent(result));
            }

            log.info("[WorkspaceTools] 输出已保存: {}", path);

            return Map.of(
                    "success", true,
                    "message", "✅ 输出已保存",
                    "path", path
            );

        } catch (Exception e) {
            log.error("[WorkspaceTools] writeStepOutput 执行失败", e);
            return Map.of("success", false, "error", "执行失败: " + e.getMessage());
        }
    }

    /**
     * 写步骤摘要
     */
    @Tool(
            name = "write_step_summary",
            description = "完成当前步骤后调用，记录核心结论。工具调用的文件会自动展示，你只需总结得出了什么结论。sessionId 和 stepIndex 会自动从上下文获取，无需传递。"
    )
    public Map<String, Object> writeStepSummary(
            @JsonProperty(value = "stepTitle", required = true) String stepTitle,
            @JsonProperty(value = "conclusion", required = true) String conclusion,
            @JsonProperty(value = "findings") List<String> findings
    ) {
        try {
            String sessionId = SessionContext.getSessionId();
            Integer stepIndex = SessionContext.getStepIndex();

            if (sessionId == null || sessionId.isEmpty()) {
                log.error("[WorkspaceTools] writeStepSummary: 无法获取 sessionId");
                return Map.of("success", false, "error", "无法获取当前会话ID");
            }

            if (stepIndex == null || stepIndex <= 0) {
                log.error("[WorkspaceTools] writeStepSummary: 无法获取 stepIndex");
                return Map.of("success", false, "error", "无法获取当前步骤索引");
            }

            log.info("[WorkspaceTools] writeStepSummary: sessionId={}, stepIndex={}, stepTitle={}",
                    sessionId, stepIndex, stepTitle);

            // 生成 summary.md 内容
            String markdown = generateSummaryMarkdown(stepTitle, conclusion, findings);

            // 构建文件路径
            String path = String.format("%s/%s/step_%03d/summary.md",
                    workspaceBasePath, sessionId, stepIndex);

            // 调用 filesystem::write_file
            Map<String, Object> args = new HashMap<>();
            args.put("path", path);
            args.put("content", markdown);

            ToolCallResult result = toolRouter.callTool("filesystem::write_file", args, sessionId);

            if (result.getIsError()) {
                log.error("[WorkspaceTools] writeStepSummary 写入失败: {}", extractTextContent(result));
                return Map.of("success", false, "error", "写入失败: " + extractTextContent(result));
            }

            log.info("[WorkspaceTools] 摘要已保存: {}", path);

            return Map.of(
                    "success", true,
                    "message", "✅ 步骤完成",
                    "path", path
            );

        } catch (Exception e) {
            log.error("[WorkspaceTools] writeStepSummary 执行失败", e);
            return Map.of("success", false, "error", "执行失败: " + e.getMessage());
        }
    }

    // ==================== 浏览类工具 ====================

    /**
     * 查看工作空间整体结构（树状图）
     */
    @Tool(
            name = "ls_workspace",
            description = "查看当前会话工作空间的目录树结构。无需参数。"
    )
    public Map<String, Object> lsWorkspace() {
        try {
            String sessionId = SessionContext.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                return Map.of("success", false, "error", "无法获取当前会话ID");
            }

            String workspacePath = workspaceBasePath + "/" + sessionId;
            log.info("[WorkspaceTools] ls_workspace: sessionId={}", sessionId);

            // 调用 filesystem::directory_tree
            Map<String, Object> args = new HashMap<>();
            args.put("path", workspacePath);

            ToolCallResult result = toolRouter.callTool("filesystem::directory_tree", args, sessionId);

            if (result.getIsError()) {
                return Map.of("success", false, "error", extractTextContent(result));
            }

            return Map.of(
                    "success", true,
                    "tree", extractTextContent(result)
            );

        } catch (Exception e) {
            log.error("[WorkspaceTools] ls_workspace failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 列出某步骤的工具文件（树状图）
     */
    @Tool(
            name = "ls_tools",
            description = "列出指定步骤的工具调用结果文件（树状结构）。参数: stepIndex (int) - 步骤编号，从1开始"
    )
    public Map<String, Object> lsTools(
            @JsonProperty(value = "stepIndex", required = true) int stepIndex
    ) {
        try {
            String sessionId = SessionContext.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                return Map.of("success", false, "error", "无法获取当前会话ID");
            }

            String toolsPath = String.format("%s/%s/step_%03d/tools",
                    workspaceBasePath, sessionId, stepIndex);

            log.info("[WorkspaceTools] ls_tools: sessionId={}, stepIndex={}", sessionId, stepIndex);

            // 调用 filesystem::directory_tree
            Map<String, Object> args = new HashMap<>();
            args.put("path", toolsPath);

            ToolCallResult result = toolRouter.callTool("filesystem::directory_tree", args, sessionId);

            if (result.getIsError()) {
                // 如果 tools 目录不存在，尝试用 list_directory
                args.put("path", String.format("%s/%s/step_%03d", workspaceBasePath, sessionId, stepIndex));
                result = toolRouter.callTool("filesystem::list_directory", args, sessionId);

                if (result.getIsError()) {
                    return Map.of("success", false, "error", "步骤目录不存在: step_" + stepIndex);
                }
            }

            return Map.of(
                    "success", true,
                    "stepIndex", stepIndex,
                    "tree", extractTextContent(result)
            );

        } catch (Exception e) {
            log.error("[WorkspaceTools] ls_tools failed: stepIndex={}", stepIndex, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }


    // ==================== 读取类工具 ====================

    /**
     * 读取某步骤的 output.md
     */
    @Tool(
            name = "cat_output",
            description = "读取指定步骤的完整输出(output.md)。参数: stepIndex (int) - 步骤编号，从1开始"
    )
    public Map<String, Object> catOutput(
            @JsonProperty(value = "stepIndex", required = true) int stepIndex
    ) {
        return readStepFile(stepIndex, "output.md");
    }

    /**
     * 读取某步骤的 summary.md
     */
    @Tool(
            name = "cat_summary",
            description = "读取指定步骤的摘要(summary.md)。参数: stepIndex (int) - 步骤编号，从1开始"
    )
    public Map<String, Object> catSummary(
            @JsonProperty(value = "stepIndex", required = true) int stepIndex
    ) {
        return readStepFile(stepIndex, "summary.md");
    }

    /**
     * 读取某步骤 tools 目录下的具体文件
     */
    @Tool(
            name = "cat_tool",
            description = "读取指定步骤的工具调用结果文件。参数: stepIndex (int) - 步骤编号; fileName (string) - 文件名，如 'duckduckgo__search_xxx.json'"
    )
    public Map<String, Object> catTool(
            @JsonProperty(value = "stepIndex", required = true) int stepIndex,
            @JsonProperty(value = "fileName", required = true) String fileName
    ) {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return Map.of("success", false, "error", "非法文件名");
        }
        return readStepFile(stepIndex, "tools/" + fileName);
    }


    // ==================== 内部方法 ====================

    /**
     * 生成摘要 Markdown
     */
    private String generateSummaryMarkdown(String stepTitle, String conclusion,
                                           List<String> findings) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(stepTitle).append("\n\n");
        md.append("## 结论\n").append(conclusion).append("\n\n");

        if (findings != null && !findings.isEmpty()) {
            md.append("## 关键发现\n");
            for (String finding : findings) {
                md.append("- ").append(finding).append("\n");
            }
            md.append("\n");
        }

        return md.toString();
    }

    /**
     * 读取步骤目录下的文件
     */
    private Map<String, Object> readStepFile(int stepIndex, String relativePath) {
        try {
            String sessionId = SessionContext.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                return Map.of("success", false, "error", "无法获取当前会话ID");
            }

            String path = String.format("%s/%s/step_%03d/%s",
                    workspaceBasePath, sessionId, stepIndex, relativePath);

            log.info("[WorkspaceTools] readStepFile: stepIndex={}, path={}", stepIndex, relativePath);

            String content = readFile(path, sessionId);
            if (content == null) {
                return Map.of(
                        "success", false,
                        "error", String.format("文件不存在: step_%03d/%s", stepIndex, relativePath)
                );
            }

            return Map.of(
                    "success", true,
                    "stepIndex", stepIndex,
                    "file", relativePath,
                    "content", content,
                    "size", content.length()
            );

        } catch (Exception e) {
            log.error("[WorkspaceTools] readStepFile failed: stepIndex={}, path={}",
                    stepIndex, relativePath, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 调用 filesystem::read_file
     */
    private String readFile(String path, String sessionId) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", path);

            ToolCallResult result = toolRouter.callTool("filesystem::read_file", args, sessionId);

            if (result.getIsError()) {
                return null;
            }

            return extractTextContent(result);

        } catch (Exception e) {
            log.debug("[WorkspaceTools] read_file exception: {}", path, e);
            return null;
        }
    }

    /**
     * 调用 filesystem::list_directory
     */
    private List<String> listDirectory(String path, String sessionId) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("path", path);

            ToolCallResult result = toolRouter.callTool("filesystem::list_directory", args, sessionId);

            if (result.getIsError()) {
                return new ArrayList<>();
            }

            String content = extractTextContent(result);
            if (content == null || content.isEmpty()) {
                return new ArrayList<>();
            }

            return Arrays.stream(content.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.equals(".") && !s.equals(".."))
                    .toList();

        } catch (Exception e) {
            log.debug("[WorkspaceTools] list_directory exception: {}", path, e);
            return new ArrayList<>();
        }
    }

    /**
     * 检查文件是否存在
     */
    private boolean fileExists(String path, String sessionId) {
        return readFile(path, sessionId) != null;
    }

    /**
     * 从 ToolCallResult 提取文本内容
     */
    private String extractTextContent(ToolCallResult result) {
        if (result == null || result.getContent() == null || result.getContent().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ToolCallResult.Content item : result.getContent()) {
            if ("text".equals(item.getType()) && item.getText() != null) {
                sb.append(item.getText());
            }
        }

        String text = sb.toString();
        return text.isEmpty() ? null : text;
    }
}
