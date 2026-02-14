package com.agent.infrastructure.llm;

import com.agent.infrastructure.persistence.entity.LLMProvider;
import com.agent.service.ChannelModelService;
import com.agent.infrastructure.persistence.entity.ChannelModel;
import com.agent.infrastructure.persistence.mapper.LLMProviderMapper;
import com.agent.common.util.EncryptionUtil;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatModel 动态工厂
 * 根据用户配置的渠道动态创建 ChatModel 实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final LLMProviderMapper providerMapper;
    private final EncryptionUtil encryptionUtil;
    private final ChannelModelService channelModelService;
    private final ObservationRegistry observationRegistry;

    /**
     * 内存缓存：key = "providerId:overrideModel"
     * 使用内存缓存而非 Redis，因为 ChatModel 不支持序列化
     */
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 创建 ChatModel (带缓存)
     * @param providerId LLM渠道ID
     * @return ChatModel实例
     */
    public ChatModel createChatModel(Long providerId) {
        return createChatModel(providerId, null);
    }

    /**
     * 创建 ChatModel (带缓存)，允许覆盖模型名称（优先使用 overrideModel）
     * @param providerId LLM渠道ID
     * @param overrideModel 如果不为空，优先使用此模型名称
     * @return ChatModel实例
     */
    public ChatModel createChatModel(Long providerId, String overrideModel) {
        String cacheKey = providerId + ":" + (overrideModel != null ? overrideModel : "");
        return chatModelCache.computeIfAbsent(cacheKey, k -> doCreateChatModel(providerId, overrideModel));
    }

    /**
     * 实际创建 ChatModel 的逻辑
     */
    private ChatModel doCreateChatModel(Long providerId, String overrideModel) {
        log.info("Creating ChatModel for provider: {} with overrideModel={}", providerId, overrideModel);

        // 1. 查询渠道配置
        LLMProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new RuntimeException("LLM Provider not found: " + providerId);
        }

        // 2. 解密 API Key
        String apiKey = provider.getApiKey() != null
            ? encryptionUtil.decrypt(provider.getApiKey())
            : null;

        // 3. 获取渠道配置的首选模型（来自 channel_models 表）
        String configuredModel = null;
        try {
            List<ChannelModel> models = channelModelService.listByChannelId(providerId);
            if (models != null && !models.isEmpty()) {
                configuredModel = models.get(0).getModelValue();
            }
        } catch (Exception ex) {
            log.warn("Failed to load channel models for provider {}: {}", providerId, ex.getMessage());
        }

        // 5. 根据类型创建对应的 ChatModel
        return switch (provider.getType()) {
            case "OPENAI", "DEEPSEEK", "ZHIPU", "CUSTOM" -> createOpenAiCompatibleModel(provider, apiKey, overrideModel != null ? overrideModel : configuredModel);
            case "AZURE" -> createAzureModel(provider, apiKey, overrideModel != null ? overrideModel : configuredModel);
            case "OLLAMA" -> createOllamaModel(provider, overrideModel != null ? overrideModel : configuredModel);
            default -> throw new RuntimeException("Unsupported provider type: " + provider.getType());
        };
    }

    /**
     * 创建 OpenAI 兼容模型
     */
    private ChatModel createOpenAiCompatibleModel(LLMProvider provider, String apiKey, String configuredModel) {
        OpenAiApi openAiApi = buildOpenAiApi(provider.getBaseUrl(), apiKey, true);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(configuredModel != null ? configuredModel : "deepseek-chat")
            .streamUsage(true)
            .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }
//    private ChatModel createOpenAiCompatibleModel(LLMProvider provider, String apiKey, String configuredModel) {
//        // RestClient 代理配置
//        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
//        requestFactory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9091)));
//        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
//
//        // WebClient 代理配置 (用于流式请求)
//        HttpClient httpClient = HttpClient.create()
//                .proxy(proxy -> proxy
//                        .type(ProxyProvider.Proxy.HTTP)
//                        .host("127.0.0.1")
//                        .port(9091));
//        WebClient.Builder webClientBuilder = WebClient.builder()
//                .clientConnector(new ReactorClientHttpConnector(httpClient));
//
//        // 使用 Builder 模式创建 OpenAiApi
//        OpenAiApi openAiApi = OpenAiApi.builder()
//                .baseUrl(provider.getBaseUrl())
//                .apiKey(apiKey)
//                .restClientBuilder(restClientBuilder)
//                .webClientBuilder(webClientBuilder)
//                .build();
//
//        OpenAiChatOptions options = OpenAiChatOptions.builder()
//                .model(configuredModel != null ? configuredModel : "deepseek-chat")
//                .streamUsage(true)
//                .build();
//
//        return new OpenAiChatModel(openAiApi, options);
//    }


    /**
     * 创建 Azure OpenAI 模型
     */
    private ChatModel createAzureModel(LLMProvider provider, String apiKey, String configuredModel) {
        // Azure 使用不同的验证方式
        // 这里需要使用 Azure 专用的 Client
        throw new UnsupportedOperationException("Azure OpenAI support coming soon");
    }

    /**
     * 创建 Ollama 模型
     */
    private ChatModel createOllamaModel(LLMProvider provider, String configuredModel) {
        // Ollama 通常不需要 API Key
        OpenAiApi openAiApi = buildOpenAiApi(provider.getBaseUrl(), null, false);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(configuredModel != null ? configuredModel : "llama2")
            .streamUsage(true)
            .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 清除缓存(当用户修改配置时调用)
     */
    public void evictCache(Long providerId) {
        log.info("Evicting ChatModel cache for provider: {}", providerId);
        chatModelCache.entrySet().removeIf(entry -> entry.getKey().startsWith(providerId + ":"));
    }

    /**
     * 清除所有缓存
     */
    public void evictAllCache() {
        log.info("Evicting all ChatModel cache");
        chatModelCache.clear();
    }

    private OpenAiApi buildOpenAiApi(String rawBaseUrl, String apiKey, boolean withApiKey) {
        EndpointParts endpointParts = resolveEndpointParts(rawBaseUrl);
        OpenAiApi.Builder builder = OpenAiApi.builder()
                .baseUrl(endpointParts.baseUrl())
                .completionsPath(endpointParts.completionsPath())
                .embeddingsPath(endpointParts.embeddingsPath());
        if (withApiKey) {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    private EndpointParts resolveEndpointParts(String rawBaseUrl) {
        String input = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        try {
            URI uri = URI.create(input);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return new EndpointParts(input, input, deriveEmbeddingsPath(input));
            }
            String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
            String completionsPath = uri.getRawPath();
            if (completionsPath == null || completionsPath.isEmpty()) {
                completionsPath = "/";
            }
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                completionsPath = completionsPath + "?" + uri.getRawQuery();
            }
            return new EndpointParts(baseUrl, completionsPath, deriveEmbeddingsPath(completionsPath));
        } catch (Exception ex) {
            log.warn("Failed to parse provider baseUrl '{}', using raw value as endpoint path.", rawBaseUrl);
            return new EndpointParts(input, input, deriveEmbeddingsPath(input));
        }
    }

    private String deriveEmbeddingsPath(String completionsPath) {
        if (completionsPath == null || completionsPath.isEmpty()) {
            return completionsPath;
        }
        int queryIndex = completionsPath.indexOf('?');
        String pathOnly = queryIndex >= 0 ? completionsPath.substring(0, queryIndex) : completionsPath;
        String query = queryIndex >= 0 ? completionsPath.substring(queryIndex) : "";
        if (pathOnly.endsWith("/chat/completions")) {
            return pathOnly.substring(0, pathOnly.length() - "/chat/completions".length()) + "/embeddings" + query;
        }
        if (pathOnly.endsWith("/completions")) {
            return pathOnly.substring(0, pathOnly.length() - "/completions".length()) + "/embeddings" + query;
        }
        return completionsPath;
    }

    private record EndpointParts(String baseUrl, String completionsPath, String embeddingsPath) {
    }
}
