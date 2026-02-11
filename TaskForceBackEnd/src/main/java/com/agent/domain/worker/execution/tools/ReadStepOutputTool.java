package com.agent.domain.worker.execution.tools;

import com.agent.domain.context.storage.WorkspaceStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 读取步骤输出工具
 * Worker 使用此工具读取其他步骤的输出内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadStepOutputTool implements ToolCallback {

    private final WorkspaceStorage workspaceStorage;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {
                "stepIndex": {
                  "type": "integer",
                  "description": "要读取的步骤索引（从 0 开始）"
                }
              },
              "required": ["stepIndex"]
            }
            """;

        return ToolDefinition.builder()
                .name("read_step_output")
                .description("读取指定步骤的输出内容，用于了解其他步骤的执行结果")
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
            int stepIndex = ((Number) args.get("stepIndex")).intValue();

            // 构建步骤输出文件路径
            String relativePath = String.format("step_%03d/output.md", stepIndex);

            // 读取文件内容
            String output = workspaceStorage.readFile(sessionId, relativePath);

            log.info("[ReadStepOutputTool] Read step output: sessionId={}, stepIndex={}",
                     sessionId, stepIndex);

            return output;

        } catch (Exception e) {
            log.error("[ReadStepOutputTool] Failed to read step output", e);
            return "Error reading step output: " + e.getMessage();
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
}
