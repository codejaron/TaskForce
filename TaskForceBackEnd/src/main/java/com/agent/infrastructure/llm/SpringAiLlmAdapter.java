package com.agent.infrastructure.llm;

import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.llm.AgentFactory;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.application.service.SessionStopService;
import com.agent.application.service.TokenUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Spring AI LLM 适配器实现
 * 封装 Spring AI ChatClient 调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiLlmAdapter implements LlmAdapter {

    private final AgentFactory agentFactory;
    private final SessionStopService sessionStopService;
    private final PromptManager promptManager;
    private final TokenUsageService tokenUsageService;
    private final AgentMapper agentMapper;

    @Override
    public Flux<String> streamChat(Long agentId, String systemPrompt, String userMessage) {
        return streamChat(agentId, null, null, systemPrompt, userMessage);
    }

    @Override
    public Flux<String> streamChat(Long agentId, String sessionId, String systemPrompt, String userMessage) {
        return streamChat(agentId, sessionId, null, systemPrompt, userMessage);
    }

    @Override
    public Flux<String> streamChat(Long agentId, String sessionId, String stepId, String systemPrompt, String userMessage) {
        log.info("[LlmAdapter] streamChat called: agentId={}, sessionId={}, stepId={}, promptLen={}",
                agentId, sessionId, stepId, systemPrompt != null ? systemPrompt.length() : 0);

        try {
            ChatClient client = agentFactory.buildClientForDatabaseAgent(agentId, sessionId != null ? sessionId : "default", stepId);
            log.info("[LlmAdapter] ChatClient created successfully");

            String prompt = promptManager.combinePrompts(systemPrompt, userMessage);

            //log.debug("[LlmAdapter] 发送给 LLM 的完整 Prompt (agentId={}):\n{}", agentId, prompt);

            // 用于累计Usage数据
            AtomicReference<Usage> usageHolder = new AtomicReference<>();

            Flux<String> stream = client.prompt()
                    .user(prompt)
                    .stream()
                    .chatResponse() // 改用chatResponse()以获取完整元数据
                    .doOnNext(chatResponse -> {
                        // 累计Usage
                        Usage usage = chatResponse.getMetadata().getUsage();
                        if (usage != null) {
                            usageHolder.set(usage);
                        }
                    })
                    .map(chatResponse -> {
                        // 提取content进行流式传输
                        String content = chatResponse.getResult().getOutput().getText();
                        return content != null ? content : "";
                    })
                    .doOnNext(token -> log.trace("[LlmAdapter] Token received: {}", token))
                    .doOnComplete(() -> {
                        log.info("[LlmAdapter] Stream completed");
                        // 流结束时异步记录Token
                        recordTokenUsageAsync(agentId, sessionId, usageHolder.get());
                    })
                    .doOnError(e -> log.error("[LlmAdapter] Stream error", e));

            // 如果有 sessionId，添加停止检查
            if (sessionId != null) {
                stream = stream.takeWhile(token -> !sessionStopService.shouldStop(sessionId));
            }

            return stream;

        } catch (Exception e) {
            log.error("[LlmAdapter] Stream chat failed: agentId={}", agentId, e);
            return Flux.error(e);
        }
    }

    @Override
    public String chat(Long agentId, String systemPrompt, String userMessage) {
        return streamChat(agentId, systemPrompt, userMessage)
                .collect(Collectors.joining())
                .block();
    }

    /**
     * 异步记录Token使用（避免阻塞流式输出）
     */
    @Async
    public void recordTokenUsageAsync(Long agentId, String sessionId, Usage usage) {
        if (usage == null) {
            log.warn("[LlmAdapter] No usage metadata available for agentId={}", agentId);
            return;
        }

        try {
            // 获取Agent配置信息
            Agent agent = agentMapper.selectById(agentId);
            if (agent == null) {
                log.error("[LlmAdapter] Agent not found: {}", agentId);
                return;
            }

            // 获取模型名称（优先使用agent.model覆盖，否则使用Provider默认）
            String modelName = agent.getModel();

            // 记录Token使用
            tokenUsageService.recordUsage(
                sessionId,
                agent.getProviderId(),
                agentId,
                modelName,
                usage.getPromptTokens().intValue(),
                usage.getCompletionTokens().intValue()
            );

            log.info("[LlmAdapter] Token usage recorded: agentId={}, model={}, prompt={}, completion={}",
                agentId, modelName, usage.getPromptTokens(), usage.getCompletionTokens());

        } catch (Exception e) {
            log.error("[LlmAdapter] Failed to record token usage", e);
            // 不抛异常，避免影响主流程
        }
    }
}
