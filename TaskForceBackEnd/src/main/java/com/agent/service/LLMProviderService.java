package com.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.dto.ChannelModelRequest;
import com.agent.dto.LLMProviderRequest;
import com.agent.entity.LLMProvider;
import com.agent.factory.ChatModelFactory;
import com.agent.mapper.LLMProviderMapper;
import com.agent.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LLM Provider 服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMProviderService {
    
    private final LLMProviderMapper providerMapper;
    private final EncryptionUtil encryptionUtil;
    private final ChatModelFactory chatModelFactory;
    private final ChannelModelService channelModelService;

    /**
     * 创建渠道
     */
    @Transactional
    public LLMProvider createProvider(LLMProviderRequest request) {
        // 加密 API Key
        String encryptedApiKey = null;
        if (request.getApiKey() != null && !request.getApiKey().isEmpty()) {
            encryptedApiKey = encryptionUtil.encrypt(request.getApiKey());
        }

        // 创建渠道
        LLMProvider provider = LLMProvider.builder()
            .name(request.getName())
            .type(request.getType())
            .baseUrl(request.getBaseUrl())
            .apiKey(encryptedApiKey)
            .config(request.getConfig())
            .build();

        providerMapper.insert(provider);

        // 如果前端传入了 models，则写入 channel_models 子表
        List<ChannelModelRequest> models = request.getModels();
        if (models != null && !models.isEmpty()) {
            channelModelService.replaceModels(provider.getId(), models);
        }

        log.info("LLM Provider created: {}", provider.getName());
        return provider;
    }
    
    /**
     * 更新渠道
     */
    @Transactional
    public LLMProvider updateProvider(Long providerId, LLMProviderRequest request) {
        // 获取Provider
        LLMProvider provider = getProviderById(providerId);

        // 更新字段
        if (request.getName() != null) {
            provider.setName(request.getName());
        }
        if (request.getType() != null) {
            provider.setType(request.getType());
        }
        if (request.getBaseUrl() != null) {
            provider.setBaseUrl(request.getBaseUrl());
        }
        if (request.getApiKey() != null && !request.getApiKey().isEmpty()) {
            provider.setApiKey(encryptionUtil.encrypt(request.getApiKey()));
        }
        if (request.getConfig() != null) {
            provider.setConfig(request.getConfig());
        }

        providerMapper.updateById(provider);

        // 如果前端提供 models，则替换子表
        List<ChannelModelRequest> models = request.getModels();
        if (models != null) {
            channelModelService.replaceModels(providerId, models);
        }

        // 清除缓存
        chatModelFactory.evictCache(providerId);

        log.info("LLM Provider updated: {}", provider.getName());
        return provider;
    }
    
    /**
     * 删除渠道
     */
    @Transactional
    public void deleteProvider(Long providerId) {
        // 获取Provider
        LLMProvider provider = getProviderById(providerId);

        // 删除
        providerMapper.deleteById(providerId);

        // 清除缓存
        chatModelFactory.evictCache(providerId);

        log.info("LLM Provider deleted: {}", provider.getName());
    }
    
    /**
     * 查询所有渠道
     */
    public List<LLMProvider> getAllProviders() {
        LambdaQueryWrapper<LLMProvider> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(LLMProvider::getCreatedAt);
        return providerMapper.selectList(wrapper);
    }

    /**
     * 根据ID查询渠道
     */
    public LLMProvider getProviderById(Long providerId) {
        LLMProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new RuntimeException("渠道不存在");
        }
        return provider;
    }
}
