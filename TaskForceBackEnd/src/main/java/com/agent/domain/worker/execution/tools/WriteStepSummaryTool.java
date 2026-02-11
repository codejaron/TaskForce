package com.agent.domain.worker.execution.tools;

import com.agent.domain.context.service.ContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 写入步骤摘要工具
 * Worker 使用此工具写入当前步骤的执行摘要
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteStepSummaryTool implements ToolCallback {

    private final ContextService contextService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "summary": {
                  "type": "string",
                  "description": "步骤执行摘要，简要描述完成的工作和结果"
                }
              },
              "required": ["summary"]
            }
            """;

        return ToolDefinition.builder()
                .name("write_step_summary")
                .description("写入当前步骤的执行摘要，用于记录工作成果供后续步骤参考")
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            Map<String, Object> args = objectMapper.readValue(toolInput, Map.class);

            String sessionId = extractSessionId(toolContext);
            Integer stepIndex = extractStepIndex(toolContext);
            String summary = (String) args.get("summary");

            // 构建摘要文件路径
            String path = String.format("step_%03d/summary.md", stepIndex);

            // 格式化摘要内容
            String formattedSummary = String.format("""
                    # Step %d

                    ## 执行摘要
                    %s
                    """, stepIndex, summary);

            // 保存摘要
            contextService.saveSummary(sessionId, path, formattedSummary);

            log.info("[WriteStepSummaryTool] Wrote step summary: sessionId={}, stepIndex={}",
                     sessionId, stepIndex);

            return "Summary written successfully";

        } catch (Exception e) {
            log.error("[WriteStepSummaryTool] Failed to write step summary", e);
            return "Error writing step summary: " + e.getMessage();
        }
    }

    private String extractSessionId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object sessionId = toolContext.getContext().get("sessionId");
            if (sessionId != null) {
                return sessionId.toString();
            }
        }
        throw new IllegalArgumentException("sessionId not found in tool context");
    }

    private Integer extractStepIndex(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object stepIndex = toolContext.getContext().get("stepIndex");
            if (stepIndex != null) {
                return Integer.parseInt(stepIndex.toString());
            }
        }
        throw new IllegalArgumentException("stepIndex not found in tool context");
    }
}
