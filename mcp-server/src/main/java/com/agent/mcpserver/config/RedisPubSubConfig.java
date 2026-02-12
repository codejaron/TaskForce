package com.agent.mcpserver.config;

import com.agent.mcpserver.listener.ProviderSyncListener;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 订阅配置
 */
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final ProviderSyncProperties syncProperties;

    @Bean
    public RedisMessageListenerContainer providerSyncListenerContainer(
            RedisConnectionFactory connectionFactory,
            ProviderSyncListener providerSyncListener
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(providerSyncListener, new ChannelTopic(syncProperties.getChannel()));
        return container;
    }
}
