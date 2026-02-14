package com.agent.mcpserver.tool;

import com.agent.mcpserver.tool.support.OutputTruncator;
import com.agent.mcpserver.tool.support.WorkspaceToolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WebSearch 工具（Exa MCP JSON-RPC + SSE 解析）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchTool {

    private static final String EXA_MCP_URL = "https://mcp.exa.ai/mcp";
    private static final int DEFAULT_NUM_RESULTS = 8;
    private static final long DEFAULT_TIMEOUT_MS = 25_000;

    private final WorkspaceToolConfig workspaceToolConfig;
    private final OutputTruncator outputTruncator;
    private final ObjectMapper objectMapper;

    @McpTool(
            name = "websearch",
            descriptionResource = "classpath:description/websearch.txt"
    )
    public Map<String, Object> websearch(
            @JsonProperty(value = "query", required = true) String query,
            @JsonProperty("numResults") Integer numResults,
            @JsonProperty("livecrawl") String livecrawl,
            @JsonProperty("type") String type,
            @JsonProperty("contextMaxCharacters") Integer contextMaxCharacters
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (query == null || query.isBlank()) {
            result.put("success", false);
            result.put("error", "query is required");
            return result;
        }

        int safeNumResults = numResults != null && numResults > 0 ? numResults : DEFAULT_NUM_RESULTS;
        safeNumResults = Math.min(Math.max(safeNumResults, 1), 20);

        String safeLivecrawl = normalizeLivecrawl(livecrawl);
        String safeType = normalizeType(type);

        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("query", query);
            args.put("numResults", safeNumResults);
            args.put("livecrawl", safeLivecrawl);
            args.put("type", safeType);
            if (contextMaxCharacters != null && contextMaxCharacters > 0) {
                args.put("contextMaxCharacters", contextMaxCharacters);
            }

            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", "web_search_exa");
            params.set("arguments", args);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("jsonrpc", "2.0");
            payload.put("id", 1);
            payload.put("method", "tools/call");
            payload.set("params", params);

            HttpRequest request = HttpRequest.newBuilder(URI.create(EXA_MCP_URL))
                    .timeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                    .header("accept", "application/json, text/event-stream")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(8_000))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = response.body() == null ? "" : response.body();
                if (body.length() > 500) {
                    body = body.substring(0, 500) + "...";
                }
                result.put("success", false);
                result.put("error", "Search error (" + response.statusCode() + "): " + body);
                return result;
            }

            String output = extractSearchText(response.body());
            output = outputTruncator.truncate(
                    output,
                    workspaceToolConfig.getMaxOutputLines(),
                    workspaceToolConfig.getMaxOutputBytes(),
                    "(Output truncated. Reduce numResults or narrow query.)"
            );

            result.put("success", true);
            result.put("query", query);
            result.put("numResults", safeNumResults);
            result.put("output", output);
            return result;
        } catch (Exception e) {
            log.warn("[WebSearchTool] search failed: query={}", query, e);
            result.put("success", false);
            result.put("error", "websearch failed: " + e.getMessage());
            return result;
        }
    }

    private String extractSearchText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "No search results found. Please try a different query.";
        }

        String[] lines = responseBody.split("\\n");
        for (String line : lines) {
            if (!line.startsWith("data: ")) {
                continue;
            }
            String jsonText = line.substring(6);
            try {
                JsonNode data = objectMapper.readTree(jsonText);
                JsonNode content = data.path("result").path("content");
                if (content.isArray() && !content.isEmpty()) {
                    String text = content.get(0).path("text").asText("");
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            } catch (Exception ignored) {
                // ignore malformed SSE event chunk
            }
        }

        // Fallback: sometimes endpoint may return plain JSON instead of SSE stream.
        try {
            JsonNode data = objectMapper.readTree(responseBody);
            JsonNode content = data.path("result").path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText("");
                if (!text.isBlank()) {
                    return text;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }

        return "No search results found. Please try a different query.";
    }

    private String normalizeLivecrawl(String livecrawl) {
        if (livecrawl == null || livecrawl.isBlank()) {
            return "fallback";
        }
        String value = livecrawl.trim().toLowerCase(Locale.ROOT);
        if ("fallback".equals(value) || "preferred".equals(value)) {
            return value;
        }
        return "fallback";
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "auto";
        }
        String value = type.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(value) || "fast".equals(value) || "deep".equals(value)) {
            return value;
        }
        return "auto";
    }
}
