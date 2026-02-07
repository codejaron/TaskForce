package com.agent.infrastructure.sandbox;

import com.alibaba.cloud.ai.sandbox.RuntimeFunctionToolCallback;
import com.alibaba.cloud.ai.sandbox.tools.base.SaaBasePythonRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * Python 执行器
 * 使用 Spring AI Alibaba Sandbox 安全执行 Python 代码
 */
@Slf4j
public class SaaPythonExecutor extends SaaBasePythonRunner {

    public SaaPythonExecutor() {
        super();
        log.debug("SaaPythonExecutor initialized");
    }

    /**
     * 构建工具回调
     * 返回 RuntimeFunctionToolCallback 用于注入到 Agent
     */
    @Override
    public RuntimeFunctionToolCallback<?, ?> buildTool() {
        log.debug("Building Python executor tool");
        return super.buildTool();
    }
}
