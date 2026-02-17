package com.agent.infrastructure.storage.minio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * MinIO 同步服务的本地实现。
 * 每轮上传 bundle 对象（按 commitHash 版本化）并更新 latest.json。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalMinioRoundSyncService implements MinioRoundSyncService {

    private final ObjectMapper objectMapper;

    @Value("${sandbox.sync.minio.enabled:false}")
    private boolean minioEnabled;

    @Value("${sandbox.sync.minio.endpoint:}")
    private String minioEndpoint;

    @Value("${sandbox.sync.minio.access-key:}")
    private String minioAccessKey;

    @Value("${sandbox.sync.minio.secret-key:}")
    private String minioSecretKey;

    @Value("${sandbox.sync.minio.bucket:taskforce-round-sync}")
    private String minioBucket;

    @Value("${sandbox.sync.storage-dir:${java.io.tmpdir}/taskforce-round-sync}")
    private String storageDir;

    @Value("${sandbox.sync.fail-round-id:}")
    private String failRoundId;

    private volatile MinioClient minioClient;
    private volatile boolean bucketReady = false;

    @Override
    public void flushRound(RoundSyncRequest request) throws Exception {
        if (request == null) {
            return;
        }
        if (request.roundId() != null && request.roundId().equals(failRoundId)) {
            throw new IllegalStateException("Simulated minio sync failure for roundId=" + request.roundId());
        }
        if (request.bundleBytes() == null || request.bundleBytes().length == 0) {
            throw new IllegalArgumentException("bundleBytes is empty");
        }
        if (request.commitHash() == null || request.commitHash().isBlank()) {
            throw new IllegalArgumentException("commitHash is blank");
        }

        Path basePath = Path.of(storageDir);
        Path sessionPath = basePath.resolve(safeSegment(request.sessionId()));
        Path bundlesPath = sessionPath.resolve("bundles");
        Files.createDirectories(bundlesPath);

        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("sessionId", request.sessionId());
        latest.put("roundId", request.roundId());
        latest.put("commitHash", request.commitHash());
        latest.put("syncedAt", Instant.now().toString());

        byte[] latestPayload = objectMapper.writeValueAsString(latest).getBytes(StandardCharsets.UTF_8);
        String bundleObject = "sessions/%s/bundles/%s.bundle"
                .formatted(safeSegment(request.sessionId()), safeSegment(request.commitHash()));
        String latestObject = "sessions/%s/latest.json".formatted(safeSegment(request.sessionId()));

        if (isMinioConfigured()) {
            uploadToMinio(bundleObject, request.bundleBytes(), "application/octet-stream");
            uploadToMinio(latestObject, latestPayload, "application/json");
            return;
        }

        Path bundleOut = bundlesPath.resolve(safeSegment(request.commitHash()) + ".bundle");
        Files.write(bundleOut, request.bundleBytes());
        Path latestOut = sessionPath.resolve("latest.json");
        Files.write(latestOut, latestPayload);
        log.debug("[RoundSync] Wrote local bundle snapshot: bundle={}, latest={}", bundleOut, latestOut);
    }

    @Override
    public Optional<RoundRestoreSnapshot> loadLatestSnapshot(String sessionId) throws Exception {
        if (!notBlank(sessionId)) {
            return Optional.empty();
        }
        if (isMinioConfigured()) {
            return loadLatestFromMinio(sessionId);
        }
        return loadLatestFromLocal(sessionId);
    }

    private void uploadToMinio(String objectPath, byte[] payload, String contentType) throws Exception {
        MinioClient client = minioClient();
        ensureBucket(client);
        client.putObject(PutObjectArgs.builder()
                .bucket(minioBucket)
                .object(objectPath)
                .stream(new ByteArrayInputStream(payload), payload.length, -1)
                .contentType(contentType)
                .build());
        log.debug("[RoundSync] Uploaded object to MinIO: bucket={}, object={}", minioBucket, objectPath);
    }

    private boolean isMinioConfigured() {
        return minioEnabled
                && notBlank(minioEndpoint)
                && notBlank(minioAccessKey)
                && notBlank(minioSecretKey)
                && notBlank(minioBucket);
    }

    private MinioClient minioClient() {
        if (minioClient != null) {
            return minioClient;
        }
        synchronized (this) {
            if (minioClient == null) {
                minioClient = MinioClient.builder()
                        .endpoint(minioEndpoint)
                        .credentials(minioAccessKey, minioSecretKey)
                        .build();
            }
        }
        return minioClient;
    }

    private void ensureBucket(MinioClient client) throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(minioBucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(minioBucket).build());
            }
            bucketReady = true;
        }
    }

    private String safeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private Optional<RoundRestoreSnapshot> loadLatestFromMinio(String sessionId) throws Exception {
        MinioClient client = minioClient();
        ensureBucket(client);
        String safeSession = safeSegment(sessionId);
        String latestObject = "sessions/%s/latest.json".formatted(safeSession);
        byte[] latestPayload;
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(minioBucket)
                .object(latestObject)
                .build())) {
            latestPayload = in.readAllBytes();
        } catch (Exception e) {
            if (isMissingObjectError(e)) {
                return Optional.empty();
            }
            throw e;
        }

        LatestPointer pointer = parseLatestPointer(latestPayload);
        if (pointer == null || !notBlank(pointer.commitHash())) {
            return Optional.empty();
        }

        String bundleObject = "sessions/%s/bundles/%s.bundle".formatted(safeSession, safeSegment(pointer.commitHash()));
        byte[] bundleBytes;
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(minioBucket)
                .object(bundleObject)
                .build())) {
            bundleBytes = in.readAllBytes();
        } catch (Exception e) {
            if (isMissingObjectError(e)) {
                log.warn("[RoundSync] latest exists but bundle missing: sessionId={}, commitHash={}",
                        sessionId, pointer.commitHash());
                return Optional.empty();
            }
            throw e;
        }

        return Optional.of(new RoundRestoreSnapshot(
                sessionId,
                pointer.roundId(),
                pointer.commitHash(),
                bundleBytes
        ));
    }

    private Optional<RoundRestoreSnapshot> loadLatestFromLocal(String sessionId) throws Exception {
        Path sessionPath = Path.of(storageDir).resolve(safeSegment(sessionId));
        Path latestPath = sessionPath.resolve("latest.json");
        if (!Files.exists(latestPath)) {
            return Optional.empty();
        }

        LatestPointer pointer = parseLatestPointer(Files.readAllBytes(latestPath));
        if (pointer == null || !notBlank(pointer.commitHash())) {
            return Optional.empty();
        }

        Path bundlesPath = sessionPath.resolve("bundles");
        Path bundlePath = bundlesPath.resolve(safeSegment(pointer.commitHash()) + ".bundle");
        if (!Files.exists(bundlePath)) {
            bundlePath = findLatestBundleFile(bundlesPath).orElse(null);
            if (bundlePath == null) {
                log.warn("[RoundSync] latest exists but no bundle in local storage: sessionId={}", sessionId);
                return Optional.empty();
            }
        }

        byte[] bundleBytes = Files.readAllBytes(bundlePath);
        String commitHash = fileCommitHash(bundlePath.getFileName().toString(), pointer.commitHash());
        return Optional.of(new RoundRestoreSnapshot(
                sessionId,
                pointer.roundId(),
                commitHash,
                bundleBytes
        ));
    }

    private LatestPointer parseLatestPointer(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            var node = objectMapper.readTree(payload);
            String roundId = node.path("roundId").asText(null);
            String commitHash = node.path("commitHash").asText(null);
            return new LatestPointer(roundId, commitHash);
        } catch (Exception e) {
            log.warn("[RoundSync] Failed to parse latest.json: {}", e.getMessage());
            return null;
        }
    }

    private Optional<Path> findLatestBundleFile(Path bundlesPath) {
        if (bundlesPath == null || !Files.exists(bundlesPath)) {
            return Optional.empty();
        }
        try (var stream = Files.list(bundlesPath)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".bundle"))
                    .max(Comparator.comparingLong(this::safeLastModifiedMillis));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private long safeLastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String fileCommitHash(String filename, String defaultHash) {
        if (!notBlank(filename)) {
            return defaultHash;
        }
        if (filename.endsWith(".bundle")) {
            return filename.substring(0, filename.length() - ".bundle".length());
        }
        return defaultHash;
    }

    private boolean isMissingObjectError(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String msg = e.getMessage().toLowerCase();
        return msg.contains("no such key")
                || msg.contains("not found")
                || msg.contains("404")
                || msg.contains("does not exist");
    }

    private record LatestPointer(String roundId, String commitHash) {
    }
}
