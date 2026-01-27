package com.agent.mcpserver.service.provider;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * STDIO 工具提供者
 * 管理通过 npx 等子进程启动的 MCP Server
 */
@Slf4j
public class StdioToolProvider extends AbstractToolProvider {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private McpSyncClient mcpClient;

    @Override
    protected void doInitialize(ToolProviderConfig config) throws Exception {
        // 解析命令参数
        List<String> args = parseArgs(config.getArgs());
        Map<String, String> env = parseEnv(config.getEnv());

        // 构建启动参数
        List<String> fullArgs = buildFullArgs(config.getCommand(), args, env);

        // 创建 MCP 客户端
        ServerParameters serverParams = ServerParameters.builder("/bin/sh")
                .args(fullArgs.toArray(new String[0]))
                .build();

        StdioClientTransport transport = new StdioClientTransport(serverParams);

        mcpClient = McpClient.sync(transport)
                .requestTimeout(DEFAULT_TIMEOUT)
                .build();

        // 初始化连接
        mcpClient.initialize();

        // 获取工具列表
        McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
        if (toolsResult != null && toolsResult.tools() != null) {
            for (McpSchema.Tool tool : toolsResult.tools()) {
                ToolDefinition toolDef = ToolDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(tool.inputSchema())
                        .build();
                registerTool(toolDef);
            }
        }
    }

    @Override
    protected void doShutdown() {
        if (mcpClient != null) {
            try {
                mcpClient.close();
            } catch (Exception e) {
                log.warn("[STDIO] Error closing MCP client: {}", e.getMessage());
            }
            mcpClient = null;
        }
    }

    @Override
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments, String sessionId) {
        if (!connected || mcpClient == null) {
            return ToolCallResult.error("Provider not connected");
        }

        if (!hasTool(toolName)) {
            return ToolCallResult.error("Tool not found: " + toolName);
        }

        try {
            // 调用 MCP 工具
            McpSchema.CallToolResult result = mcpClient.callTool(
                    new McpSchema.CallToolRequest(toolName, arguments)
            );

            // 转换结果
            if (result.isError() != null && result.isError()) {
                String errorMsg = extractTextContent(result.content());
                return ToolCallResult.error(errorMsg);
            }

            String textContent = extractTextContent(result.content());
            return ToolCallResult.text(textContent);

        } catch (Exception e) {
            log.error("[STDIO] Tool call failed: {} - {}", toolName, e.getMessage(), e);
            return ToolCallResult.error("Tool call failed: " + e.getMessage());
        }
    }

    /**
     * 构建完整的命令参数
     */
    private List<String> buildFullArgs(String command, List<String> args, Map<String, String> env) {
        StringBuilder cmd = new StringBuilder();

        // 添加环境变量
        for (Map.Entry<String, String> entry : env.entrySet()) {
            cmd.append(entry.getKey())
                    .append("=")
                    .append(escapeShellArg(entry.getValue()))
                    .append(" ");
        }

        // 添加命令
        cmd.append(escapeShellArg(command));
        for (String arg : args) {
            cmd.append(" ").append(escapeShellArg(arg));
        }

        return List.of("-c", cmd.toString());
    }

    /**
     * Shell 参数转义
     */
    private String escapeShellArg(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * 解析命令参数 JSON
     */
    @SuppressWarnings("unchecked")
    private List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[STDIO] Failed to parse args JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析环境变量 JSON
     */
    private Map<String, String> parseEnv(String envJson) {
        if (envJson == null || envJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, String> env = objectMapper.readValue(envJson, new TypeReference<Map<String, String>>() {});
            // 解析环境变量占位符
            Map<String, String> resolved = new HashMap<>();
            for (Map.Entry<String, String> entry : env.entrySet()) {
                String value = entry.getValue();
                if (value.startsWith("${") && value.endsWith("}")) {
                    String varName = value.substring(2, value.length() - 1);
                    String systemValue = System.getenv(varName);
                    if (systemValue != null) {
                        resolved.put(entry.getKey(), systemValue);
                    }
                } else {
                    resolved.put(entry.getKey(), value);
                }
            }
            return resolved;
        } catch (Exception e) {
            log.warn("[STDIO] Failed to parse env JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 提取文本内容
     */
    private String extractTextContent(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : contents) {
            if (content instanceof McpSchema.TextContent textContent) {
                sb.append(textContent.text());
            }
        }
        return sb.toString();
    }
}
