package com.agent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * A safe fallback ChatModel used when no real LLM provider is configured.
 *
 * The application can still start, but any attempt to call/stream will fail fast
 * with a clear configuration error.
 */
public class DisabledChatModel implements ChatModel {

    private final IllegalStateException disabledError;

    public DisabledChatModel(String message) {
        this.disabledError = new IllegalStateException(message);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw disabledError;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.error(disabledError);
    }
}
