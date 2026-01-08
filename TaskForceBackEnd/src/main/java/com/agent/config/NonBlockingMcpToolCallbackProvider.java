package com.agent.config;

import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;

/**
 * 非阻塞 MCP 工具回调提供者
 *
 * 包装原始的 SyncMcpToolCallbackProvider，将所有工具回调
 * 包装为 NonBlockingMcpToolCallback，确保在 WebFlux 环境中安全执行。
 */
public class NonBlockingMcpToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private FunctionCallback[] wrappedCallbacks;

    public NonBlockingMcpToolCallbackProvider(ToolCallbackProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public FunctionCallback[] getToolCallbacks() {
        if (wrappedCallbacks == null) {
            FunctionCallback[] original = delegate.getToolCallbacks();
            wrappedCallbacks = Arrays.stream(original)
                    .map(fc -> {
                        if (fc instanceof ToolCallback tc) {
                            return (FunctionCallback) new NonBlockingMcpToolCallback(tc);
                        }
                        return fc;
                    })
                    .toArray(FunctionCallback[]::new);
        }
        return wrappedCallbacks;
    }
}

