package com.agent.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML Artifact 解析器
 * 负责从 Worker 输出中提取 <artifact key="xxx">value</artifact> 标签
 *
 * 功能：
 * - 使用正则表达式提取 XML 格式的 artifact 标签
 * - 支持跨行内容
 * - 支持大小写不敏感匹配
 * - 自动过滤无效的 artifact（空 key）
 * - 对超大 value 进行截断保护（10MB 限制）
 */
@Slf4j
public class ArtifactParser {

    /**
     * 正则表达式：匹配 <artifact key="xxx">...</artifact>
     * - 大小写不敏感
     * - 支持跨行内容
     * - key 支持字母、数字、下划线、横线
     */
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile(
        "<artifact\\s+key=\"([a-zA-Z0-9_-]+)\"\\s*>([\\s\\S]*?)</artifact>",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 最大 value 长度限制：10MB
     */
    private static final int MAX_VALUE_LENGTH = 10_000_000;

    /**
     * 提取所有 Artifact
     *
     * @param text 待解析的文本
     * @return Artifact 列表
     */
    public static List<Artifact> extract(String text) {
        List<Artifact> results = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return results;
        }

        try {
            Matcher matcher = ARTIFACT_PATTERN.matcher(text);
            int count = 0;

            while (matcher.find()) {
                String key = matcher.group(1).trim();
                String value = matcher.group(2).trim();

                // 验证 key
                if (key.isEmpty()) {
                    log.warn("[ArtifactParser] Skipped artifact with empty key");
                    continue;
                }

                // 长度限制：避免超大 value（10MB）
                if (value.length() > MAX_VALUE_LENGTH) {
                    log.warn("[ArtifactParser] Artifact key='{}' too large ({}B), truncating",
                            key, value.length());
                    value = value.substring(0, MAX_VALUE_LENGTH) + "\n[... truncated due to size limit]";
                }

                results.add(new Artifact(key, value));
                count++;
            }

            if (count > 0) {
                log.info("[ArtifactParser] Extracted {} artifact(s)", count);
            }

        } catch (Exception e) {
            log.error("[ArtifactParser] Failed to parse artifacts", e);
        }

        return results;
    }

    /**
     * 移除文本中的所有 Artifact 标签，替换为占位符
     * 用于清理历史消息，避免重复发送 Artifact 内容
     *
     * @param text 待处理的文本
     * @return 替换 artifact 标签后的文本
     */
    public static String removeArtifacts(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }

        try {
            // 替换 artifact 标签为占位符
            Matcher matcher = ARTIFACT_PATTERN.matcher(text);
            StringBuffer result = new StringBuffer();

            while (matcher.find()) {
                String key = matcher.group(1);
                matcher.appendReplacement(result, "[Artifact 已保存: " + key + "]");
            }
            matcher.appendTail(result);

            // 清理多余的空行（连续3个及以上换行替换为2个换行）
            String cleaned = result.toString().replaceAll("\n{3,}", "\n\n");
            return cleaned.trim();
        } catch (Exception e) {
            log.error("[ArtifactParser] Failed to remove artifacts", e);
            return text;
        }
    }

    /**
     * Artifact 数据类
     * 封装 key-value 对
     */
    @Data
    public static class Artifact {
        private final String key;
        private final String value;

        public Artifact(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
