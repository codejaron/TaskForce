package com.agent.infrastructure.storage.minio;

/**
 * latest 指针对应的可恢复快照。
 */
public record RoundRestoreSnapshot(
        String sessionId,
        String roundId,
        String commitHash,
        byte[] bundleBytes
) {
}
