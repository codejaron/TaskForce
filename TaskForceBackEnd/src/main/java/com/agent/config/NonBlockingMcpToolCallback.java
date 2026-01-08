package com.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.*;

/**
 * 非阻塞 MCP 工具回调包装器
 *
 * 将阻塞的 SyncMcpToolCallback 包装起来，使用独立的线程池执行阻塞操作，
 * 避免在 Netty NIO 线程中调用 block() 导致的 IllegalStateException。
 *
 * 核心思路：不使用 Reactor 的 Mono.block()，而是使用 Java 标准的 ExecutorService
 * 来真正地在另一个线程中执行阻塞操作，然后用 Future.get() 等待结果。
 */
@Slf4j
public class NonBlockingMcpToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    // 使用专用的线程池来执行 MCP 工具调用
    // 这个线程池允许阻塞操作
    private static final ExecutorService TOOL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("mcp-tool-executor-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    public NonBlockingMcpToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String toolName = getToolDefinition().name();

        // 验证并修正输入参数
        String validatedInput = validateAndFixInput(toolInput, toolName);

        log.debug("Calling MCP tool: {} with input: {}", toolName, validatedInput);
        return executeInSeparateThread(() -> delegate.call(validatedInput), toolName, validatedInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = getToolDefinition().name();

        // 验证并修正输入参数
        String validatedInput = validateAndFixInput(toolInput, toolName);

        log.debug("Calling MCP tool: {} with input: {} and context", toolName, validatedInput);
        return executeInSeparateThread(() -> delegate.call(validatedInput, toolContext), toolName, validatedInput);
    }

    /**
     * 验证并修正工具输入参数
     * 如果输入为 null 或空字符串，返回空 JSON 对象
     */
    private String validateAndFixInput(String toolInput, String toolName) {
        if (toolInput == null || toolInput.trim().isEmpty()) {
            log.warn("MCP tool {} received empty input, using empty JSON object {{}}", toolName);
            return "{}";
        }
        return toolInput;
    }

    /**
     * 在独立线程中执行阻塞操作
     * 使用 Future.get() 而不是 Mono.block()，因为 Future.get() 不受 Reactor 的线程限制检查
     */
    private String executeInSeparateThread(Callable<String> task, String toolName, String toolInput) {
        try {
            Future<String> future = TOOL_EXECUTOR.submit(task);
            // 设置超时，避免无限等待
            String result = future.get(120, TimeUnit.SECONDS);

            // 处理空响应或 null
            if (result == null || result.trim().isEmpty()) {
                log.warn("MCP tool {} returned empty response for input: {}", toolName, toolInput);
                return "{}"; // 返回空 JSON 对象而不是 null
            }

            log.debug("MCP tool {} completed successfully, result length: {}", toolName, result.length());
            return result;
        } catch (TimeoutException e) {
            log.error("MCP tool {} timed out after 120 seconds, input: {}", toolName, toolInput);
            throw new RuntimeException("MCP tool call timed out after 120 seconds: " + toolName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("MCP tool {} failed, input: {}, error: {}", toolName, toolInput, cause.getMessage(), cause);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("MCP tool call failed: " + toolName, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("MCP tool {} was interrupted, input: {}", toolName, toolInput);
            throw new RuntimeException("MCP tool call was interrupted: " + toolName, e);
        }
    }
}

