package com.agent.infrastructure.llm;

import reactor.core.publisher.Flux;

/**
 * LLM 适配器接口
 * 封装 LLM 调用，支持流式和阻塞模式
 */
public interface LlmAdapter {

    /**
     * 流式调用 LLM
     *
     * @param agentId      Agent ID
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息（可为 null）
     * @return 流式 token 输出
     */
    Flux<String> streamChat(Long agentId, String systemPrompt, String userMessage);

    /**
     * 阻塞式调用 LLM
     *
     * @param agentId      Agent ID
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息（可为 null）
     * @return 完整响应
     */
    String chat(Long agentId, String systemPrompt, String userMessage);

    /**
     * 流式调用 LLM（使用 sessionId 管理会话记忆）
     *
     * @param agentId      Agent ID
     * @param sessionId    会话 ID
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息（可为 null）
     * @return 流式 token 输出
     */
    Flux<String> streamChat(Long agentId, String sessionId, String systemPrompt, String userMessage);

    /**
     * 流式调用 LLM（使用 sessionId 和 stepId，支持工具调用事件追踪）
     *
     * @param agentId      Agent ID
     * @param sessionId    会话 ID
     * @param stepId       步骤 ID（用于工具调用事件关联）
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息（可为 null）
     * @return 流式 token 输出
     */
    Flux<String> streamChat(Long agentId, String sessionId, String stepId, String systemPrompt, String userMessage);

    /**
     * 流式调用 LLM（使用 sessionId、stepId 和 stepIndex，支持工具调用事件追踪）
     *
     * @param agentId      Agent ID
     * @param sessionId    会话 ID
     * @param stepId       步骤 ID（用于工具调用事件关联）
     * @param stepIndex    步骤索引（用于工具调用事件关联）
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息（可为 null）
     * @return 流式 token 输出
     */
    Flux<String> streamChat(Long agentId, String sessionId, String stepId, Integer stepIndex, String systemPrompt, String userMessage);
}
