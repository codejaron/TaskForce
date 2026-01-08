package com.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 客户端配置
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    /**
     * 聊天记忆 - 支持多轮对话上下文
     */
    @Bean
    public InMemoryChatMemory chatMemory() {
        return new InMemoryChatMemory();
    }

    /**
     * 配置ChatClient，注入Advisors实现递归思考循环
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 InMemoryChatMemory chatMemory) {
        return builder
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build();
    }
}
