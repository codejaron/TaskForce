package com.agent.infrastructure.llm;

import com.agent.factory.AgentFactory;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.service.SessionStopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

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

    @Override
    public Flux<String> streamChat(Long agentId, String systemPrompt, String userMessage) {
        return streamChat(agentId, null, systemPrompt, userMessage);
    }

    @Override
    public Flux<String> streamChat(Long agentId, String sessionId, String systemPrompt, String userMessage) {
        log.info("[LlmAdapter] streamChat called: agentId={}, sessionId={}, promptLen={}",
                agentId, sessionId, systemPrompt != null ? systemPrompt.length() : 0);

        try {
            ChatClient client = agentFactory.buildClientForDatabaseAgent(agentId, sessionId != null ? sessionId : "default");
            log.info("[LlmAdapter] ChatClient created successfully");

            String prompt = promptManager.combinePrompts(systemPrompt, userMessage);

            log.debug("[LlmAdapter] 发送给 LLM 的完整 Prompt (agentId={}):\n{}", agentId, prompt);

            Flux<String> stream = client.prompt()
                    .user(prompt)
                    .stream()
                    .content()
                    .doOnNext(token -> log.trace("[LlmAdapter] Token received: {}", token))
                    .doOnComplete(() -> log.info("[LlmAdapter] Stream completed"))
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
}
