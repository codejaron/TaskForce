package com.agent.mcpserver.service;

import com.agent.mcpserver.entity.ToolProviderConfig;
import com.agent.mcpserver.mapper.ToolProviderConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具提供者配置服务
 * 支持从数据库和配置文件加载配置
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolProviderConfigService extends ServiceImpl<ToolProviderConfigMapper, ToolProviderConfig> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${mcp.config-path:./mcp-server-config.json}")
    private String configPath;

    @Value("${mcp.config-source:database}")
    private String configSource; // database, file, both

    @PostConstruct
    public void init() {
        log.info("[ConfigService] Config source: {}", configSource);
        if ("file".equals(configSource) || "both".equals(configSource)) {
            loadFromFile();
        }
    }

    /**
     * 获取所有启用的配置
     */
    public List<ToolProviderConfig> listEnabledConfigs() {
        List<ToolProviderConfig> configs = new ArrayList<>();

        // 从数据库加载
        if ("database".equals(configSource) || "both".equals(configSource)) {
            QueryWrapper<ToolProviderConfig> wrapper = new QueryWrapper<>();
            wrapper.eq("enabled", true);
            configs.addAll(list(wrapper));
        }

        // 从配置文件加载
        if ("file".equals(configSource) || "both".equals(configSource)) {
            configs.addAll(loadFromFile());
        }

        return configs;
    }

    /**
     * 从配置文件加载
     */
    private List<ToolProviderConfig> loadFromFile() {
        List<ToolProviderConfig> configs = new ArrayList<>();
        Path path = Path.of(configPath);

        if (!Files.exists(path)) {
            log.info("[ConfigService] Config file not found: {}", configPath);
            return configs;
        }

        try {
            String json = Files.readString(path);
            McpServerConfigFile configFile = objectMapper.readValue(json, McpServerConfigFile.class);

            if (configFile.providers != null) {
                for (Map.Entry<String, ProviderConfigEntry> entry : configFile.providers.entrySet()) {
                    String key = entry.getKey();
                    ProviderConfigEntry value = entry.getValue();

                    if (!value.enabled) {
                        continue;
                    }

                    ToolProviderConfig config = ToolProviderConfig.builder()
                            .id("file::" + key)
                            .name(key)
                            .type(ToolProviderConfig.ProviderType.valueOf(value.type.toUpperCase()))
                            .enabled(value.enabled)
                            .description(value.description)
                            .command(value.command)
                            .args(value.args != null ? objectMapper.writeValueAsString(value.args) : null)
                            .env(value.env != null ? objectMapper.writeValueAsString(value.env) : null)
                            .beanName(value.beanName)
                            .className(value.className)
                            .sseUrl(value.sseUrl)
                            .headers(value.headers != null ? objectMapper.writeValueAsString(value.headers) : null)
                            .timeout(value.timeout)
                            .build();

                    configs.add(config);
                    log.info("[ConfigService] Loaded provider from file: {}", key);
                }
            }
        } catch (Exception e) {
            log.error("[ConfigService] Failed to load config file: {}", configPath, e);
        }

        return configs;
    }

    /**
     * 更新连接状态
     */
    public void updateConnectionStatus(String providerId, boolean connected, int toolCount, String errorMessage) {
        // 只更新数据库中的配置
        if (providerId.startsWith("file::")) {
            return;
        }

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

    /**
     * 配置文件结构
     */
    private static class McpServerConfigFile {
        public Map<String, ProviderConfigEntry> providers;
    }

    private static class ProviderConfigEntry {
        public String type;
        public boolean enabled = true;
        public String description;
        
        // STDIO
        public String command;
        public List<String> args;
        public Map<String, String> env;
        
        // NATIVE
        public String beanName;
        public String className;
        
        // REMOTE_SSE
        public String sseUrl;
        public Map<String, String> headers;
        public Integer timeout;
    }
}
