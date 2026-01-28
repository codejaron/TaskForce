package com.agent.mcpserver.listener;

import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.service.ToolProviderConfigService;
import com.agent.mcpserver.service.ToolRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "mcp-provider-sync",
        consumerGroup = "mcp-server-${random.value}"
)
public class ProviderSyncListener implements RocketMQListener<String> {

    private final ToolRouter toolRouter;
    private final ToolProviderConfigService configService;

    @Override
    public void onMessage(String message) {
        log.info("[ProviderSync] Received: {}", message);

        try {
            // 消息格式: "add:providerId" 或 "delete:providerId"
            String[] parts = message.split(":", 2);
            if (parts.length != 2) {
                log.warn("[ProviderSync] Invalid message format: {}", message);
                return;
            }

            String action = parts[0];
            String providerId = parts[1];

            switch (action) {
                case "add" -> handleAdd(providerId);
                case "delete" -> handleDelete(providerId);
                default -> log.warn("[ProviderSync] Unknown action: {}", action);
            }
        } catch (Exception e) {
            log.error("[ProviderSync] Failed to process message: {}", message, e);
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
