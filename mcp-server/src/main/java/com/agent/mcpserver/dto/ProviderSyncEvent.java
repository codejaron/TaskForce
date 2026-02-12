package com.agent.mcpserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider 同步事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderSyncEvent {
    private String action;
    private String providerId;
    private String sourceInstanceId;
    private long timestamp;
}
