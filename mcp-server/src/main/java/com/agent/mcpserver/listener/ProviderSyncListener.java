package com.agent.mcpserver.listener;

import com.agent.mcpserver.config.ProviderSyncProperties;
import com.agent.mcpserver.dto.ProviderSyncEvent;
import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.service.ToolProviderConfigService;
import com.agent.mcpserver.service.ToolRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderSyncListener implements MessageListener {

    private final ToolRouter toolRouter;
    private final ToolProviderConfigService configService;
    private final ProviderSyncProperties syncProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        log.debug("[ProviderSync] Received: {}", payload);

        try {
            ProviderSyncEvent event = objectMapper.readValue(payload, ProviderSyncEvent.class);
            if (event.getProviderId() == null || event.getProviderId().isBlank()) {
                log.warn("[ProviderSync] Invalid event payload: {}", payload);
                return;
            }

            if (syncProperties.getInstanceId().equals(event.getSourceInstanceId())) {
                return;
            }

            switch (event.getAction()) {
                case "add" -> handleAdd(event.getProviderId());
                case "delete" -> handleDelete(event.getProviderId());
                default -> log.warn("[ProviderSync] Unknown action: {}", event.getAction());
            }
        } catch (Exception e) {
            log.error("[ProviderSync] Failed to process message: {}", payload, e);
        }
    }

    private void handleAdd(String providerId) {
        // 从数据库读取配置
        ToolProviderConfig config = configService.getById(providerId);
        if (config == null) {
            log.warn("[ProviderSync] Provider not found in DB: {}", providerId);
            return;
        }

        try {
            toolRouter.registerProvider(config);
            log.info("[ProviderSync] Added provider: {}", providerId);
        } catch (Exception e) {
            log.error("[ProviderSync] Failed to add provider: {}", providerId, e);
        }
    }

    private void handleDelete(String providerId) {
        toolRouter.unregisterProvider(providerId);
        log.info("[ProviderSync] Deleted provider: {}", providerId);
    }
}
