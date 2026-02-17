package com.agent.infrastructure.storage.minio;

import java.util.Optional;

/**
 * 轮次产物同步服务。
 */
public interface MinioRoundSyncService {

    void flushRound(RoundSyncRequest request) throws Exception;

    Optional<RoundRestoreSnapshot> loadLatestSnapshot(String sessionId) throws Exception;
}
