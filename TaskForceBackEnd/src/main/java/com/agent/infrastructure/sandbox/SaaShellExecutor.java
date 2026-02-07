package com.agent.infrastructure.sandbox;

import com.alibaba.cloud.ai.sandbox.RuntimeFunctionToolCallback;
import com.alibaba.cloud.ai.sandbox.tools.base.SaaBaseShellRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * Shell 执行器
 * 使用 Spring AI Alibaba Sandbox 安全执行 Shell 命令
 */
@Slf4j
public class SaaShellExecutor extends SaaBaseShellRunner {

    public SaaShellExecutor() {
        super();
        log.debug("SaaShellExecutor initialized");
    }

    /**
     * 构建工具回调
     * 返回 RuntimeFunctionToolCallback 用于注入到 Agent
     */
    @Override
    public RuntimeFunctionToolCallback<?, ?> buildTool() {
        log.debug("Building Shell executor tool");
        return super.buildTool();
    }
}
