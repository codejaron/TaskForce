package com.agent.infrastructure.storage.minio;

/**
 * 轮次文件同步请求。
 */
public record RoundSyncRequest(
        String sessionId,
        String roundId,
        String commitHash,
        byte[] bundleBytes
) {
}
