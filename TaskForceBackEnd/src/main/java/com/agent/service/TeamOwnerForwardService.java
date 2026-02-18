package com.agent.service;

import com.agent.api.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Forward Team APIs to owner node when current node is not owner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamOwnerForwardService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${cluster.forward-timeout-seconds:10}")
    private long forwardTimeoutSeconds;

    public <T> ApiResponse<T> forward(String ownerNodeId,
                                      HttpMethod method,
                                      String path,
                                      Object body,
                                      Class<T> dataType) {
        try {
            String baseUrl = normalizeBaseUrl(ownerNodeId);
            WebClient client = webClientBuilder.baseUrl(baseUrl).build();

            WebClient.RequestBodyUriSpec methodSpec = client.method(method);
            WebClient.RequestHeadersSpec<?> req;
            if (body != null && allowsBody(method)) {
                req = methodSpec.uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body);
            } else {
                req = methodSpec.uri(path);
            }

            String raw = req.retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(1L, forwardTimeoutSeconds)));

            return parseApiResponse(raw, dataType);
        } catch (Exception e) {
            log.error("[TeamOwnerForward] Forward failed: owner={}, method={}, path={}", ownerNodeId, method, path, e);
            return ApiResponse.error("Forward to owner failed: " + e.getMessage());
        }
    }

    private <T> ApiResponse<T> parseApiResponse(String raw, Class<T> dataType) {
        if (raw == null || raw.isBlank()) {
            return ApiResponse.error("Forward response is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            Integer code = root.has("code") ? root.get("code").asInt(500) : 500;
            String message = root.has("message") ? root.get("message").asText("forward response missing message")
                    : "forward response missing message";

            T data = null;
            JsonNode dataNode = root.get("data");
            if (dataNode != null && !dataNode.isNull() && dataType != null && dataType != Void.class) {
                data = objectMapper.treeToValue(dataNode, dataType);
            }
            return ApiResponse.<T>builder()
                    .code(code)
                    .message(message)
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("[TeamOwnerForward] Failed to parse forward response: raw={}", raw, e);
            return ApiResponse.error("Failed to parse forward response: " + e.getMessage());
        }
    }

    private boolean allowsBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }

    private String normalizeBaseUrl(String ownerNodeId) {
        if (ownerNodeId == null || ownerNodeId.isBlank()) {
            throw new IllegalArgumentException("ownerNodeId is blank");
        }
        String normalized = ownerNodeId.trim();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized;
        }
        return "http://" + normalized;
    }
}
