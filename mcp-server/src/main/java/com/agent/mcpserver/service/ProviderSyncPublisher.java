package com.agent.mcpserver.service;

import com.agent.mcpserver.config.ProviderSyncProperties;
import com.agent.mcpserver.dto.ProviderSyncEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Provider 同步广播发布器（Redis Pub/Sub）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderSyncPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ProviderSyncProperties syncProperties;

    public void publishAdd(String providerId) {
        publish("add", providerId);
    }

    public void publishDelete(String providerId) {
        publish("delete", providerId);
    }

    private void publish(String action, String providerId) {
        ProviderSyncEvent event = ProviderSyncEvent.builder()
                .action(action)
                .providerId(providerId)
                .sourceInstanceId(syncProperties.getInstanceId())
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            String payload = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(syncProperties.getChannel(), payload);
            log.debug("[ProviderSync] Published event: {}", payload);
        } catch (Exception e) {
            log.error("[ProviderSync] Failed to publish event: action={}, providerId={}", action, providerId, e);
        }
    }
}
