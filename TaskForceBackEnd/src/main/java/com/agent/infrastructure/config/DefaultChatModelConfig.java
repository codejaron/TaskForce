package com.agent.infrastructure.config;

import com.agent.infrastructure.llm.DisabledChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Ensures the application always has a "default" ChatModel.
 *
 * - If a real OpenAI ChatModel bean exists (from Spring AI auto-config or custom config), use it.
 * - Otherwise provide a DisabledChatModel so the app can start and fail fast only when LLM features are used.
 */
@Configuration
public class DefaultChatModelConfig {

    @Bean
    @Primary
    @ConditionalOnBean(OpenAiChatModel.class)
    public ChatModel defaultChatModel(OpenAiChatModel openAiChatModel) {
        return openAiChatModel;
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel disabledDefaultChatModel() {
        return new DisabledChatModel(
                "No LLM provider is configured. Please configure an LLM provider (including API Key) in the database to enable chat features."
        );
    }
}
