package com.agent.mcpserver.tool;

import com.agent.mcpserver.tool.support.OutputTruncator;
import com.agent.mcpserver.tool.support.WorkspaceToolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WebFetch 工具（按 OpenCode webfetch.ts 行为翻译，结合当前服务返回结构）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebFetchTool {

    private static final long MAX_RESPONSE_SIZE = 5L * 1024 * 1024;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final long MAX_TIMEOUT_MS = 120_000;

    private final WorkspaceToolConfig workspaceConfig;
    private final OutputTruncator outputTruncator;

    @McpTool(
            name = "webfetch",
            descriptionResource = "classpath:description/webfetch.txt"
    )
    public Map<String, Object> webfetch(
            @JsonProperty(value = "url", required = true) String url,
            @JsonProperty("format") String format,
            @JsonProperty("timeout") Long timeout
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (url == null || url.isBlank()) {
            result.put("success", false);
            result.put("error", "url is required");
            return result;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            result.put("success", false);
            result.put("error", "URL must start with http:// or https://");
            return result;
        }

        String safeFormat = normalizeFormat(format);
        if (safeFormat == null) {
            result.put("success", false);
            result.put("error", "format must be one of: text, markdown, html");
            return result;
        }

        long timeoutMs = Math.min((timeout == null ? 30 : timeout) * 1000, MAX_TIMEOUT_MS);
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 10_000)))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            String acceptHeader = acceptHeader(safeFormat);
            HttpRequest initialRequest = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", chromeUserAgent())
                    .header("Accept", acceptHeader)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build();

            HttpResponse<InputStream> initialResponse = client.send(initialRequest, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response = initialResponse;

            String cfMitigated = initialResponse.headers().firstValue("cf-mitigated").orElse("");
            if (initialResponse.statusCode() == 403 && "challenge".equalsIgnoreCase(cfMitigated)) {
                closeQuietly(initialResponse.body());
                HttpRequest retryRequest = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("User-Agent", "opencode")
                        .header("Accept", acceptHeader)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build();
                response = client.send(retryRequest, HttpResponse.BodyHandlers.ofInputStream());
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                result.put("success", false);
                result.put("error", "Request failed with status code: " + response.statusCode());
                return result;
            }

            String contentType = response.headers().firstValue("content-type").orElse("");
            String contentLength = response.headers().firstValue("content-length").orElse("");
            if (!contentLength.isBlank()) {
                try {
                    long len = Long.parseLong(contentLength);
                    if (len > MAX_RESPONSE_SIZE) {
                        closeQuietly(response.body());
                        result.put("success", false);
                        result.put("error", "Response too large (exceeds 5MB limit)");
                        return result;
                    }
                } catch (NumberFormatException ignored) {
                    // ignore malformed content-length
                }
            }

            byte[] body;
            try (InputStream inputStream = response.body()) {
                body = readWithLimit(inputStream, MAX_RESPONSE_SIZE);
            }
            if (body.length > MAX_RESPONSE_SIZE) {
                result.put("success", false);
                result.put("error", "Response too large (exceeds 5MB limit)");
                return result;
            }

            String mime = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            boolean isImage = mime.startsWith("image/")
                    && !"image/svg+xml".equals(mime)
                    && !"image/vnd.fastbidsheet".equals(mime);

            result.put("success", true);
            result.put("url", url);
            result.put("title", url + " (" + contentType + ")");
            result.put("contentType", contentType);
            result.put("format", safeFormat);

            if (isImage) {
                result.put("content", "Image fetched successfully");
                result.put("mime", mime);
                result.put("base64", Base64.getEncoder().encodeToString(body));
                return result;
            }

            String content = new String(body, StandardCharsets.UTF_8);
            String output = switch (safeFormat) {
                case "markdown" -> contentType.contains("text/html") ? convertHtmlToMarkdown(content, url) : content;
                case "text" -> contentType.contains("text/html") ? extractTextFromHtml(content) : content;
                case "html" -> content;
                default -> content;
            };

            output = outputTruncator.truncate(
                    output,
                    workspaceConfig.getMaxOutputLines(),
                    workspaceConfig.getMaxOutputBytes(),
                    "(Output truncated. Narrow the page scope or use a different format.)"
            );

            result.put("content", output);
            return result;
        } catch (Exception e) {
            log.warn("[WebFetchTool] failed: {}", url, e);
            result.put("success", false);
            result.put("error", "webfetch failed: " + e.getMessage());
            return result;
        }
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "markdown";
        }
        String value = format.trim().toLowerCase(Locale.ROOT);
        if ("text".equals(value) || "markdown".equals(value) || "html".equals(value)) {
            return value;
        }
        return null;
    }

    private String chromeUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    }

    private String acceptHeader(String format) {
        return switch (format) {
            case "markdown" -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
            case "text" -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1";
            case "html" -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1";
            default -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
        };
    }

    private byte[] readWithLimit(InputStream inputStream, long maxBytes) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException("Response too large (exceeds 5MB limit)");
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private String extractTextFromHtml(String html) {
        Document document = Jsoup.parse(html);
        document.select("script,style,noscript,iframe,object,embed").remove();
        return document.text().trim();
    }

    private String convertHtmlToMarkdown(String html, String baseUrl) {
        Document document = Jsoup.parse(html, baseUrl);
        document.select("script,style,meta,link").remove();

        Element body = document.body();
        if (body == null) {
            return "";
        }

        StringBuilder markdown = new StringBuilder();
        for (Element child : body.children()) {
            appendBlock(child, markdown, 0);
        }

        return markdown.toString().replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private void appendBlock(Element element, StringBuilder builder, int listDepth) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Integer.parseInt(tag.substring(1));
                builder.append("#".repeat(level)).append(" ")
                        .append(inlineText(element).trim())
                        .append("\n\n");
            }
            case "p" -> builder.append(inlineText(element).trim()).append("\n\n");
            case "pre" -> builder.append("```\n").append(element.text()).append("\n```\n\n");
            case "blockquote" -> {
                String text = inlineText(element).trim().replace("\n", "\n> ");
                builder.append("> ").append(text).append("\n\n");
            }
            case "ul" -> {
                for (Element li : element.children()) {
                    if (!"li".equalsIgnoreCase(li.tagName())) {
                        continue;
                    }
                    builder.append("  ".repeat(Math.max(0, listDepth))).append("- ")
                            .append(inlineText(li).trim())
                            .append("\n");
                    for (Element nested : li.children()) {
                        if ("ul".equalsIgnoreCase(nested.tagName()) || "ol".equalsIgnoreCase(nested.tagName())) {
                            appendBlock(nested, builder, listDepth + 1);
                        }
                    }
                }
                builder.append("\n");
            }
            case "ol" -> {
                int idx = 1;
                for (Element li : element.children()) {
                    if (!"li".equalsIgnoreCase(li.tagName())) {
                        continue;
                    }
                    builder.append("  ".repeat(Math.max(0, listDepth))).append(idx++).append(". ")
                            .append(inlineText(li).trim())
                            .append("\n");
                    for (Element nested : li.children()) {
                        if ("ul".equalsIgnoreCase(nested.tagName()) || "ol".equalsIgnoreCase(nested.tagName())) {
                            appendBlock(nested, builder, listDepth + 1);
                        }
                    }
                }
                builder.append("\n");
            }
            case "hr" -> builder.append("---\n\n");
            default -> {
                if (element.children().isEmpty()) {
                    String text = element.text().trim();
                    if (!text.isEmpty()) {
                        builder.append(text).append("\n\n");
                    }
                } else {
                    for (Element child : element.children()) {
                        appendBlock(child, builder, listDepth);
                    }
                }
            }
        }
    }

    private String inlineText(Element element) {
        StringBuilder builder = new StringBuilder();
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode textNode) {
                builder.append(textNode.text());
                continue;
            }
            if (node instanceof Element child) {
                String tag = child.tagName().toLowerCase(Locale.ROOT);
                if ("a".equals(tag)) {
                    String text = child.text().trim();
                    String href = child.absUrl("href");
                    if (href == null || href.isBlank()) {
                        href = child.attr("href");
                    }
                    if (text.isEmpty()) {
                        builder.append(href);
                    } else if (href == null || href.isBlank()) {
                        builder.append(text);
                    } else {
                        builder.append("[").append(text).append("](").append(href).append(")");
                    }
                } else if ("code".equals(tag)) {
                    builder.append("`").append(child.text()).append("`");
                } else if ("br".equals(tag)) {
                    builder.append("\n");
                } else {
                    builder.append(inlineText(child));
                }
            }
        }
        return builder.toString().replaceAll("[\\t ]+", " ");
    }

    private void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
