package com.agent.api.controller;

import com.agent.api.request.ChannelModelRequest;
import com.agent.infrastructure.persistence.entity.ChannelModel;
import com.agent.api.response.ApiResponse;
import com.agent.api.request.LLMProviderRequest;
import com.agent.api.request.RemoteModelsRequest;
import com.agent.infrastructure.persistence.entity.LLMProvider;
import com.agent.service.LLMProviderService;
import com.agent.service.ChannelModelService;
import com.agent.common.util.EncryptionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM Provider 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/providers")
@RequiredArgsConstructor
public class LLMProviderController {

    private final LLMProviderService providerService;
    private final ChannelModelService channelModelService;
    private final EncryptionUtil encryptionUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    
    /**
     * 创建渠道
     */
    @PostMapping
    public ApiResponse<LLMProvider> createProvider(@Valid @RequestBody LLMProviderRequest request) {
        try {
            LLMProvider provider = providerService.createProvider(request);
            // 返回脱敏的 API Key
            if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
                provider.setApiKey(maskApiKey(provider.getApiKey()));
            }
            return ApiResponse.success("渠道创建成功", provider);
        } catch (Exception e) {
            log.error("Create provider failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 更新渠道
     */
    @PutMapping("/{providerId}")
    public ApiResponse<LLMProvider> updateProvider(
        @PathVariable Long providerId,
        @Valid @RequestBody LLMProviderRequest request
    ) {
        try {
            LLMProvider provider = providerService.updateProvider(providerId, request);
            // 返回脱敏的 API Key
            if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
                provider.setApiKey(maskApiKey(provider.getApiKey()));
            }
            return ApiResponse.success("渠道更新成功", provider);
        } catch (Exception e) {
            log.error("Update provider failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 删除渠道
     */
    @DeleteMapping("/{providerId}")
    public ApiResponse<Void> deleteProvider(@PathVariable Long providerId) {
        try {
            providerService.deleteProvider(providerId);
            return ApiResponse.success("渠道删除成功", null);
        } catch (Exception e) {
            log.error("Delete provider failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 获取所有渠道
     */
    @GetMapping
    public ApiResponse<List<LLMProvider>> getAllProviders() {
        try {
            List<LLMProvider> providers = providerService.getAllProviders();
            // 返回脱敏的 API Key
            providers.forEach(p -> {
                if (p.getApiKey() != null && !p.getApiKey().isEmpty()) {
                    p.setApiKey(maskApiKey(p.getApiKey()));
                }
            });
            return ApiResponse.success(providers);
        } catch (Exception e) {
            log.error("Get all providers failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取渠道详情
     */
    @GetMapping("/{providerId}")
    public ApiResponse<LLMProvider> getProvider(@PathVariable Long providerId) {
        try {
            LLMProvider provider = providerService.getProviderById(providerId);
            // 返回脱敏的 API Key
            if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
                provider.setApiKey(maskApiKey(provider.getApiKey()));
            }
            return ApiResponse.success(provider);
        } catch (Exception e) {
            log.error("Get provider failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取解密后的完整 API Key
     */
    @GetMapping("/{providerId}/api-key")
    public ApiResponse<Map<String, String>> getDecryptedApiKey(@PathVariable Long providerId) {
        try {
            LLMProvider provider = providerService.getProviderById(providerId);
            String encryptedApiKey = provider.getApiKey();
            String decryptedApiKey = "";

            if (encryptedApiKey != null && !encryptedApiKey.isEmpty()) {
                decryptedApiKey = encryptionUtil.decrypt(encryptedApiKey);
            }

            return ApiResponse.success(Map.of("apiKey", decryptedApiKey));
        } catch (Exception e) {
            log.error("Get decrypted API key failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取渠道的模型列表
     */
    @GetMapping("/{providerId}/models")
    public ApiResponse<List<ChannelModel>> getProviderModels(@PathVariable Long providerId) {
        try {
            List<ChannelModel> models = channelModelService.listByChannelId(providerId);
            return ApiResponse.success(models);
        } catch (Exception e) {
            log.error("Get provider models failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 添加模型到渠道
     */
    @PostMapping("/{providerId}/models")
    public ApiResponse<ChannelModel> createProviderModel(
            @PathVariable Long providerId,
            @Valid @RequestBody ChannelModelRequest request) {
        try {
            ChannelModel model = channelModelService.createModel(providerId, request);
            return ApiResponse.success("模型添加成功", model);
        } catch (Exception e) {
            log.error("Create provider model failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除渠道的模型
     */
    @DeleteMapping("/{providerId}/models/{modelId}")
    public ApiResponse<Void> deleteProviderModel(
            @PathVariable Long providerId,
            @PathVariable Long modelId) {
        try {
            channelModelService.deleteModel(modelId);
            return ApiResponse.success("模型删除成功", null);
        } catch (Exception e) {
            log.error("Delete provider model failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 替换渠道的所有模型
     */
    @PutMapping("/{providerId}/models")
    public ApiResponse<List<ChannelModel>> replaceProviderModels(
            @PathVariable Long providerId,
            @RequestBody List<ChannelModelRequest> models) {
        try {
            List<ChannelModel> result = channelModelService.replaceModels(providerId, models);
            return ApiResponse.success("模型更新成功", result);
        } catch (Exception e) {
            log.error("Replace provider models failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ========== 远程模型获取（合并自 RemoteModelFetchController）==========

    /**
     * 从任意 OpenAI 兼容的 API 获取模型列表
     * 用于创建新渠道时预览可用模型
     */
    @PostMapping("/fetch-models")
    public ApiResponse<List<Map<String, String>>> fetchModels(@RequestBody RemoteModelsRequest req) {
        try {
            String baseUrl = req.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                return ApiResponse.error("baseUrl is required");
            }

            List<String> candidates = buildModelFetchCandidates(baseUrl);
            String lastError = null;

            for (String full : candidates) {
                try {
                    HttpRequest.Builder rb = HttpRequest.newBuilder()
                            .uri(URI.create(full))
                            .timeout(Duration.ofSeconds(10))
                            .GET();
                    if (req.getApiKey() != null && !req.getApiKey().isEmpty()) {
                        rb.header("Authorization", "Bearer " + req.getApiKey());
                    }
                    rb.header("Accept", "application/json");

                    HttpRequest httpRequest = rb.build();
                    HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 400) {
                        lastError = "Failed to fetch models: " + resp.statusCode() + " " + resp.body();
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(resp.body());
                    List<Map<String, String>> out = new ArrayList<>();
                    if (root.has("data") && root.get("data").isArray()) {
                        for (JsonNode item : root.get("data")) {
                            if (item.has("id")) {
                                String id = item.get("id").asText();
                                out.add(Map.of("modelValue", id, "displayName", id));
                            }
                        }
                    }
                    return ApiResponse.success(out);
                } catch (IllegalArgumentException e) {
                    lastError = "Invalid model URL: " + full;
                } catch (IOException e) {
                    lastError = e.getMessage();
                }
            }

            return ApiResponse.error(lastError != null ? lastError : "Failed to fetch models");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to fetch remote models", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 为已存在的渠道获取远程模型列表
     * 使用渠道存储的 baseUrl 和解密后的 API key
     */
    @GetMapping("/{providerId}/fetch-remote")
    public ApiResponse<List<Map<String, String>>> fetchModelsForProvider(@PathVariable Long providerId) {
        try {
            LLMProvider p = providerService.getProviderById(providerId);
            if (p == null) return ApiResponse.error("Provider not found");
            String base = p.getBaseUrl();
            String encryptedApiKey = p.getApiKey();
            String apiKey = null;
            if (encryptedApiKey != null && !encryptedApiKey.isEmpty()) {
                apiKey = encryptionUtil.decrypt(encryptedApiKey);
            }
            RemoteModelsRequest req = RemoteModelsRequest.builder().baseUrl(base).apiKey(apiKey).build();
            return fetchModels(req);
        } catch (Exception e) {
            log.error("Failed to fetch models for provider {}", providerId, e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 脱敏 API Key，只显示前后几位
     * 例如：sk-1234567890abcdef -> sk-****cdef
     */
    private String maskApiKey(String encryptedApiKey) {
        if (encryptedApiKey == null || encryptedApiKey.isEmpty()) {
            return "";
        }

        try {
            // 先解密
            String apiKey = encryptionUtil.decrypt(encryptedApiKey);

            if (apiKey.length() <= 8) {
                return "****";
            }

            // 显示前缀（如 sk-）和最后4位
            String prefix = "";
            String suffix = apiKey.substring(apiKey.length() - 4);

            // 如果是 OpenAI 格式的 key (sk-xxx)
            if (apiKey.startsWith("sk-")) {
                prefix = "sk-";
            } else if (apiKey.length() > 4) {
                prefix = apiKey.substring(0, Math.min(4, apiKey.length() - 4));
            }

            return prefix + "****" + suffix;
        } catch (Exception e) {
            log.error("Failed to mask API key", e);
            return "****";
        }
    }

    private List<String> buildModelFetchCandidates(String rawBaseUrl) {
        String base = trimTrailingSlash(rawBaseUrl.trim());
        Set<String> candidates = new LinkedHashSet<>();

        // If user entered a chat completion endpoint, convert it to a models endpoint first.
        if (base.endsWith("/v1/chat/completions")) {
            candidates.add(base.substring(0, base.length() - "/chat/completions".length()) + "/models");
        } else if (base.endsWith("/chat/completions")) {
            candidates.add(base.substring(0, base.length() - "/chat/completions".length()) + "/models");
        }

        String stripped = stripBaseTail(base);
        if (!stripped.isEmpty()) {
            candidates.add(stripped + "/models");
            candidates.add(stripped + "/v1/models");
        }

        if (base.endsWith("/v1")) {
            candidates.add(base + "/models");
        } else if (base.endsWith("/models")) {
            candidates.add(base);
        }

        // Last fallback: use exactly what user provided.
        candidates.add(base);
        return new ArrayList<>(candidates);
    }

    private String stripBaseTail(String base) {
        if (base.endsWith("/v1/chat/completions")) {
            return base.substring(0, base.length() - "/v1/chat/completions".length());
        }
        if (base.endsWith("/chat/completions")) {
            return base.substring(0, base.length() - "/chat/completions".length());
        }
        if (base.endsWith("/v1")) {
            return base.substring(0, base.length() - "/v1".length());
        }
        return base;
    }

    private String trimTrailingSlash(String input) {
        String result = input;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
