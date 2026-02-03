package com.agent.mcpserver.tool;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.service.ToolRouter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 写步骤摘要工具（Native Tool）
 * 内部调用 MCP filesystem::write_file
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriteStepSummaryTool {
    
    private final ToolRouter toolRouter;
    
    @Value("${context.workspace.base-path:/workspace}")
    private String workspaceBasePath;
    
    /**
     * 写步骤摘要
     */
    @Tool(
        name = "write_step_summary",
        description = "完成当前步骤后调用，记录核心结论。工具调用的文件会自动展示，你只需总结得出了什么结论。sessionId 会自动从上下文获取，无需传递。"
    )
    public Map<String, Object> writeStepSummary(
            @JsonProperty(value = "stepIndex", required = true) int stepIndex,
            @JsonProperty(value = "stepTitle", required = true) String stepTitle,
            @JsonProperty(value = "conclusion", required = true) String conclusion,
            @JsonProperty(value = "findings") List<String> findings,
            @JsonProperty(value = "nextSuggestion") String nextSuggestion
    ) {
        try {
            // 从 ThreadLocal 或请求上下文获取 sessionId
            // 注意：这里需要一个机制来传递 sessionId，暂时使用占位符
            String sessionId = getCurrentSessionId();
            
            if (sessionId == null || sessionId.isEmpty()) {
                log.error("[WriteStepSummaryTool] 无法获取 sessionId");
                return Map.of(
                        "success", false,
                        "error", "无法获取当前会话ID"
                );
            }
            
            log.info("[WriteStepSummaryTool] 写入步骤摘要: sessionId={}, stepIndex={}, stepTitle={}", 
                    sessionId, stepIndex, stepTitle);
            
            // 生成 summary.md 内容
            String markdown = generateSummaryMarkdown(stepTitle, conclusion, findings, nextSuggestion);
            
            // 构建文件路径
            String path = String.format("%s/%s/step_%03d/summary.md", 
                    workspaceBasePath, sessionId, stepIndex);
            
            // 调用 MCP filesystem::write_file
            Map<String, Object> args = new HashMap<>();
            args.put("path", path);
            args.put("content", markdown);
            
            ToolCallResult result = toolRouter.callTool("filesystem::write_file", args, sessionId);
            
            if (result.getIsError()) {
                log.error("[WriteStepSummaryTool] 写入失败: {}", result.getContent());
                return Map.of(
                        "success", false,
                        "error", "写入失败: " + result.getContent()
                );
            }
            
            log.info("[WriteStepSummaryTool] 摘要已保存: {}", path);
            
            return Map.of(
                    "success", true,
                    "message", "✅ 摘要已保存到 " + path,
                    "path", path
            );
            
        } catch (Exception e) {
            log.error("[WriteStepSummaryTool] 执行失败", e);
            return Map.of(
                    "success", false,
                    "error", "执行失败: " + e.getMessage()
            );
        }
    }
    
    /**
     * 从上下文获取当前 sessionId
     */
    private String getCurrentSessionId() {
        return com.agent.mcpserver.context.SessionContext.getSessionId();
    }
    
    /**
     * 生成摘要 Markdown
     */
    private String generateSummaryMarkdown(String stepTitle, String conclusion,
                                           List<String> findings, String nextSuggestion) {
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
        
        if (nextSuggestion != null && !nextSuggestion.isEmpty()) {
            md.append("## 下一步建议\n").append(nextSuggestion).append("\n");
        }
        
        return md.toString();
    }
}
