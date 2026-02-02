package com.agent.infrastructure.mcp.tool;

import com.agent.infrastructure.persistence.entity.SessionArtifact;
import com.agent.common.context.SessionContextHolder;
import com.agent.infrastructure.persistence.mapper.SessionArtifactMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Artifact 工具服务
 * 提供 LLM 可调用的 Artifact 查询工具
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArtifactTools {

    private final SessionArtifactMapper artifactMapper;

    /**
     * 查询指定 key 的 Artifact 完整内容
     */
    @Tool(
        name = "native::query_artifact",
        description = "查询指定 key 的 Artifact 完整内容。当你需要查看某个 Artifact 的详细内容时使用此工具。参数: key (string, required) - Artifact 的键名"
    )
    public Map<String, Object> queryArtifact(@JsonProperty(value = "key", required = true) String key) {
        try {
            String sessionId = SessionContextHolder.getSessionId();
            log.info("[ArtifactTools] query_artifact called: sessionId={}, key={}", sessionId, key);

            QueryWrapper<SessionArtifact> wrapper = new QueryWrapper<>();
            wrapper.eq("session_id", sessionId).eq("artifact_key", key);
            SessionArtifact artifact = artifactMapper.selectOne(wrapper);

            if (artifact == null) {
                log.warn("[ArtifactTools] Artifact not found: sessionId={}, key={}", sessionId, key);
                return Map.of(
                    "success", false,
                    "error", "Artifact not found: " + key
                );
            }

            log.info("[ArtifactTools] Artifact found: key={}, size={} chars",
                key, artifact.getArtifactValue().length());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("key", artifact.getArtifactKey());
            result.put("value", artifact.getArtifactValue());
            result.put("size", artifact.getArtifactValue().length());
            result.put("updatedAt", artifact.getUpdatedAt().toString());

            return result;

        } catch (IllegalStateException e) {
            log.error("[ArtifactTools] SessionId not set in current thread", e);
            return Map.of(
                "success", false,
                "error", "Internal error: Session context not available"
            );
        } catch (Exception e) {
            log.error("[ArtifactTools] Failed to query artifact: key={}", key, e);
            return Map.of(
                "success", false,
                "error", "Failed to query artifact: " + e.getMessage()
            );
        }
    }

    /**
     * 列出当前会话中所有可用的 Artifact
     */
    @Tool(
        name = "native::list_artifacts",
        description = "列出当前会话中所有可用的 Artifact keys 和摘要。用于了解有哪些数据可用。无需参数。"
    )
    public Map<String, Object> listArtifacts() {
        try {
            String sessionId = SessionContextHolder.getSessionId();
            log.info("[ArtifactTools] list_artifacts called: sessionId={}", sessionId);

            QueryWrapper<SessionArtifact> wrapper = new QueryWrapper<>();
            wrapper.eq("session_id", sessionId).orderByDesc("updated_at");
            List<SessionArtifact> artifacts = artifactMapper.selectList(wrapper);

            List<Map<String, Object>> items = artifacts.stream()
                .map(a -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("key", a.getArtifactKey());
                    item.put("size", a.getArtifactValue().length());

                    // 预览前 100 字符
                    String preview = a.getArtifactValue().length() > 100
                        ? a.getArtifactValue().substring(0, 100) + "..."
                        : a.getArtifactValue();
                    item.put("preview", preview);
                    item.put("updatedAt", a.getUpdatedAt().toString());

                    return item;
                })
                .collect(Collectors.toList());

            log.info("[ArtifactTools] Found {} artifacts", items.size());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("artifacts", items);
            result.put("count", items.size());

            return result;

        } catch (IllegalStateException e) {
            log.error("[ArtifactTools] SessionId not set in current thread", e);
            return Map.of(
                "success", false,
                "error", "Internal error: Session context not available"
            );
        } catch (Exception e) {
            log.error("[ArtifactTools] Failed to list artifacts", e);
            return Map.of(
                "success", false,
                "error", "Failed to list artifacts: " + e.getMessage()
            );
        }
    }

    /**
     * 根据关键词搜索 Artifact
     */
    @Tool(
        name = "native::search_artifacts",
        description = "根据关键词搜索 Artifact。返回包含关键词的 Artifact keys。参数: keyword (string, required) - 搜索关键词。"
    )
    public Map<String, Object> searchArtifacts(@JsonProperty(value = "keyword", required = true) String keyword) {
        try {
            String sessionId = SessionContextHolder.getSessionId();
            log.info("[ArtifactTools] search_artifacts called: sessionId={}, keyword={}", sessionId, keyword);

            QueryWrapper<SessionArtifact> wrapper = new QueryWrapper<>();
            wrapper.eq("session_id", sessionId)
                   .and(w -> w.like("artifact_key", keyword).or().like("artifact_value", keyword))
                   .orderByDesc("updated_at");

            List<SessionArtifact> artifacts = artifactMapper.selectList(wrapper);

            List<Map<String, Object>> matches = artifacts.stream()
                .map(a -> {
                    Map<String, Object> match = new HashMap<>();
                    match.put("key", a.getArtifactKey());
                    match.put("size", a.getArtifactValue().length());

                    // 预览匹配的部分
                    String value = a.getArtifactValue();
                    int keywordIndex = value.toLowerCase().indexOf(keyword.toLowerCase());
                    String preview;
                    if (keywordIndex >= 0) {
                        int start = Math.max(0, keywordIndex - 50);
                        int end = Math.min(value.length(), keywordIndex + keyword.length() + 50);
                        preview = (start > 0 ? "..." : "") + value.substring(start, end) + (end < value.length() ? "..." : "");
                    } else {
                        preview = value.substring(0, Math.min(100, value.length())) + "...";
                    }
                    match.put("preview", preview);

                    return match;
                })
                .collect(Collectors.toList());

            log.info("[ArtifactTools] Found {} matching artifacts", matches.size());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("matches", matches);
            result.put("count", matches.size());
            result.put("keyword", keyword);

            return result;

        } catch (IllegalStateException e) {
            log.error("[ArtifactTools] SessionId not set in current thread", e);
            return Map.of(
                "success", false,
                "error", "Internal error: Session context not available"
            );
        } catch (Exception e) {
            log.error("[ArtifactTools] Failed to search artifacts: keyword={}", keyword, e);
            return Map.of(
                "success", false,
                "error", "Failed to search artifacts: " + e.getMessage()
            );
        }
    }
}
