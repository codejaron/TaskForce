package com.agent.mcpserver.tool.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文本输出截断工具。
 */
@Component
public class OutputTruncator {

    public String truncate(String text, int maxLines, int maxBytes, String suffix) {
        if (text == null) {
            return "";
        }
        String normalizedSuffix = suffix == null ? "" : suffix;
        boolean truncated = false;

        String[] lines = text.split("\\R", -1);
        int safeMaxLines = Math.max(maxLines, 1);
        StringBuilder builder = new StringBuilder();
        int lineLimit = Math.min(lines.length, safeMaxLines);
        for (int i = 0; i < lineLimit; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        if (lines.length > safeMaxLines) {
            truncated = true;
        }

        String lineLimited = builder.toString();
        byte[] bytes = lineLimited.getBytes(StandardCharsets.UTF_8);
        int safeMaxBytes = Math.max(maxBytes, 1);
        if (bytes.length > safeMaxBytes) {
            int cut = safeMaxBytes;
            while (cut > 0 && (bytes[cut - 1] & 0b1100_0000) == 0b1000_0000) {
                cut--;
            }
            if (cut <= 0) {
                cut = Math.min(safeMaxBytes, bytes.length);
            }
            lineLimited = new String(bytes, 0, cut, StandardCharsets.UTF_8);
            truncated = true;
        }

        if (truncated && !normalizedSuffix.isBlank()) {
            return lineLimited + "\n" + normalizedSuffix;
        }
        return lineLimited;
    }

    /**
     * 中间截断：头尾各保留一半，中间插入 marker。
     * 先按行数阈值截断，再按字节阈值截断。
     */
    public String truncateHeadTail(String text, int maxLines, int maxBytes, String marker) {
        if (text == null) {
            return "";
        }
        String safeMarker = (marker == null || marker.isBlank()) ? "[...truncated...]" : marker;
        String result = text;
        boolean truncated = false;

        int safeMaxLines = Math.max(1, maxLines);
        String[] lines = result.split("\\R", -1);
        if (lines.length > safeMaxLines) {
            truncated = true;
            int head = Math.max(1, safeMaxLines / 2);
            int tail = Math.max(0, safeMaxLines - head);

            List<String> merged = new ArrayList<>();
            for (int i = 0; i < head && i < lines.length; i++) {
                merged.add(lines[i]);
            }
            merged.add(safeMarker);
            for (int i = Math.max(head, lines.length - tail); i < lines.length; i++) {
                merged.add(lines[i]);
            }
            result = String.join("\n", merged);
        }

        int safeMaxBytes = Math.max(1, maxBytes);
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > safeMaxBytes) {
            truncated = true;
            int headBytes = Math.max(1, safeMaxBytes / 2);
            int tailBytes = Math.max(0, safeMaxBytes - headBytes);

            String head = utf8Prefix(result, headBytes);
            String tail = utf8Suffix(result, tailBytes);

            if (!head.endsWith("\n")) {
                head = head + "\n";
            }
            if (!tail.startsWith("\n") && !tail.isEmpty()) {
                tail = "\n" + tail;
            }
            result = head + safeMarker + tail;
        }

        return truncated ? result : text;
    }

    private String utf8Prefix(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut - 1] & 0b1100_0000) == 0b1000_0000) {
            cut--;
        }
        if (cut <= 0) {
            cut = Math.min(maxBytes, bytes.length);
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8);
    }

    private String utf8Suffix(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int start = bytes.length - maxBytes;
        while (start < bytes.length && (bytes[start] & 0b1100_0000) == 0b1000_0000) {
            start++;
        }
        if (start >= bytes.length) {
            start = Math.max(0, bytes.length - maxBytes);
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }
}
