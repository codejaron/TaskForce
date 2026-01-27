package com.agent.mcpserver.service;

import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.mapper.ToolProviderConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具提供者配置服务
 * 只从数据库加载配置
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolProviderConfigService extends ServiceImpl<ToolProviderConfigMapper, ToolProviderConfig> {

    /**
     * 获取所有启用的配置
     */
    public List<ToolProviderConfig> listEnabledConfigs() {
        QueryWrapper<ToolProviderConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("enabled", true);
        return list(wrapper);
    }

    /**
     * 更新连接状态
     */
    public void updateConnectionStatus(String providerId, boolean connected, int toolCount, String errorMessage) {
        try {
            ToolProviderConfig config = getById(providerId);
            if (config != null) {
                config.setConnected(connected);
                config.setToolCount(toolCount);
                config.setErrorMessage(errorMessage);
                config.setLastConnectedAt(connected ? LocalDateTime.now() : null);
                updateById(config);
            }
        } catch (Exception e) {
            log.error("[ConfigService] Failed to update connection status: {}", providerId, e);
        }
    }

    /**
     * 添加新配置
     */
    public ToolProviderConfig addConfig(ToolProviderConfig config) {
        save(config);
        return config;
    }

    /**
     * 删除配置
     */
    public void deleteConfig(String id) {
        removeById(id);
    }
}
