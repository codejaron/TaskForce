package com.agent.mcpserver.service.provider;

import com.agent.mcpserver.dto.ToolCallResult;
import com.agent.mcpserver.dto.ToolDefinition;
import com.agent.mcpserver.entity.ToolProviderConfig;

import java.util.List;
import java.util.Map;

/**
 * 工具提供者接口
 * 统一抽象 STDIO、Native Java、Remote SSE 三种工具来源
 */
public interface ToolProvider {

    /**
     * 初始化提供者
     * @param config 提供者配置
     */
    void initialize(ToolProviderConfig config) throws Exception;

    /**
     * 关闭提供者
     */
    void shutdown();

    /**
     * 获取提供者ID
     */
    String getId();

    /**
     * 获取提供者名称
     */
    String getName();

    /**
     * 获取提供者类型
     */
    ToolProviderConfig.ProviderType getType();

    /**
     * 是否已连接/可用
     */
    boolean isConnected();

    /**
     * 列出所有可用工具
     */
    List<ToolDefinition> listTools();

    /**
     * 调用工具
     * @param toolName 工具名称
     * @param arguments 调用参数
     * @param sessionId 会话ID（可选）
     * @return 调用结果
     */
    ToolCallResult callTool(String toolName, Map<String, Object> arguments, String sessionId);

    /**
     * 检查是否包含指定工具
     */
    boolean hasTool(String toolName);
}
