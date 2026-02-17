package com.agent.infrastructure.sandbox;

import com.agent.infrastructure.persistence.mapper.ToolCallMapper;
import com.agent.infrastructure.storage.minio.MinioRoundSyncService;
import com.agent.infrastructure.storage.minio.RoundRestoreSnapshot;
import com.agent.infrastructure.storage.minio.RoundSyncRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.runtime.sandbox.box.FilesystemSandbox;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Session 级别 sandbox 管理器。
 * 业务层通过强类型方法调用，不依赖 toolId 路由。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sandbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SessionSandboxManager {

    public static final String SYNCED = "SYNCED";
    public static final String SYNC_FAILED = "SYNC_FAILED";
    public static final String SYNC_LOST_RISK = "SYNC_LOST_RISK";

    private static final long[] INLINE_RETRY_BACKOFF_MS = new long[] {0L, 1000L, 2000L};
    private static final int BINARY_CHUNK_SIZE = 48 * 1024;
    private static final String WORKSPACE_LOCK_KEY_PATTERN = "workspace:%s";

    private final SandboxService sandboxService;
    private final MinioRoundSyncService minioRoundSyncService;
    private final ToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Autowired(required = false)
    @Qualifier("filesystemSandbox")
    private FilesystemSandbox sharedFilesystemSandbox;

    private final ConcurrentMap<String, FilesystemSandbox> sandboxBySession = new ConcurrentHashMap<>();
    private volatile String sharedSandboxActiveSessionId;

    public void beginRound(String sessionId, String roundId) {
        // 预热并切换到当前会话 workspace，避免首次工具调用命中旧会话内容。
        if (!isBlank(sessionId)) {
            withWorkspaceReadLock(sessionId, () -> { });
        }
    }

    public void endRound(String sessionId, String roundId) {
        // no-op
    }

    public <T> T withWorkspaceReadLock(String sessionId, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try (WorkspaceLock ignored = acquireWorkspaceReadLock(sessionId)) {
            ensureWorkspaceReady(sessionId);
            return supplier.get();
        }
    }

    public void withWorkspaceReadLock(String sessionId, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        try (WorkspaceLock ignored = acquireWorkspaceReadLock(sessionId)) {
            ensureWorkspaceReady(sessionId);
            runnable.run();
        }
    }

    public String readTextFile(String sessionId, String path, Integer offset, Integer limit) {
        return withWorkspaceReadLock(sessionId, () -> {
            String raw = requireSandbox(sessionId).readFile(path);
            String content = extractTextPayload(raw);
            return applyLineWindow(content, offset, limit);
        });
    }

    public String writeTextFile(String sessionId, String path, String content) {
        return withWorkspaceReadLock(sessionId,
                () -> extractTextPayload(requireSandbox(sessionId).writeFile(path, content == null ? "" : content)));
    }

    public String editTextFile(String sessionId, String path, String oldString, String newString, boolean replaceAll) {
        return withWorkspaceReadLock(sessionId, () -> {
            if (replaceAll) {
                String full = extractTextPayload(requireSandbox(sessionId).readFile(path));
                String replaced = full.replace(
                        oldString == null ? "" : oldString,
                        newString == null ? "" : newString
                );
                return extractTextPayload(requireSandbox(sessionId).writeFile(path, replaced));
            }
            Map<String, Object> edit = Map.of(
                    "oldText", oldString == null ? "" : oldString,
                    "newText", newString == null ? "" : newString
            );
            String raw = requireSandbox(sessionId).editFile(path, new Object[] {edit}, false);
            return extractTextPayload(raw);
        });
    }

    public void writeBinaryFile(String sessionId, String path, InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream cannot be null");
        }
        String normalizedPath = normalizePath(path);
        withWorkspaceReadLock(sessionId, () -> {
            FilesystemSandbox sandbox = requireSandbox(sessionId);
            try {
                String parent = parentDir(normalizedPath);
                runShellChecked(sandbox, "mkdir -p " + shellQuote(parent) + " && : > " + shellQuote(normalizedPath));

                byte[] buffer = new byte[BINARY_CHUNK_SIZE];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    byte[] chunk = read == buffer.length ? buffer : Arrays.copyOf(buffer, read);
                    String base64 = Base64.getEncoder().encodeToString(chunk);
                    String appendCmd = "printf %s " + shellQuote(base64)
                            + " | base64 -d >> " + shellQuote(normalizedPath);
                    runShellChecked(sandbox, appendCmd);
                }
            } catch (Exception e) {
                throw new IllegalStateException("writeBinaryFile failed: " + e.getMessage(), e);
            }
        });
    }

    public String listDirectory(String sessionId, String path) {
        return withWorkspaceReadLock(sessionId, () -> requireSandbox(sessionId).listDirectory(path));
    }

    public String searchFiles(String sessionId, String pattern, String path) {
        return withWorkspaceReadLock(sessionId, () -> requireSandbox(sessionId).searchFiles(path, pattern));
    }

    public String runShell(String sessionId, String command, Long timeoutMs, String workdir) {
        return withWorkspaceReadLock(sessionId, () -> {
            String effective = command == null ? "" : command;
            if (!isBlank(workdir)) {
                effective = "cd " + shellQuote(workdir) + " && (" + effective + ")";
            }
            if (timeoutMs != null && timeoutMs > 0) {
                long timeoutSeconds = Math.max(1L, timeoutMs / 1000L);
                effective = "timeout " + timeoutSeconds + "s bash -lc " + shellQuote(effective);
            }
            return runShellChecked(requireSandbox(sessionId), effective);
        });
    }

    /**
     * 轮次结束 flush：内联重试 3 次（立即 + 1s + 2s）。
     * 三次均失败后记为 SYNC_FAILED。
     */
    public boolean flushRound(String sessionId, String roundId) {
        if (isBlank(sessionId) || isBlank(roundId)) {
            return true;
        }

        String lastError = null;
        RoundBundleSnapshot snapshot = null;
        for (int i = 0; i < INLINE_RETRY_BACKOFF_MS.length; i++) {
            long backoff = INLINE_RETRY_BACKOFF_MS[i];
            if (backoff > 0) {
                sleepQuietly(backoff);
            }
            try {
                if (snapshot == null) {
                    snapshot = withWorkspaceWriteLock(sessionId, () -> buildRoundBundleSnapshot(sessionId, roundId));
                }
                minioRoundSyncService.flushRound(new RoundSyncRequest(
                        sessionId,
                        roundId,
                        snapshot.commitHash(),
                        snapshot.bundleBytes()
                ));
                markRoundSynced(sessionId, roundId);
                endRound(sessionId, roundId);
                return true;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[SessionSandbox] flushRound failed: sessionId={}, roundId={}, attempt={}/3, err={}",
                        sessionId, roundId, i + 1, lastError);
            }
        }

        markRoundSyncFailed(sessionId, roundId, lastError, false);
        endRound(sessionId, roundId);
        return false;
    }

    public boolean hasPendingDirty(String sessionId) {
        return false;
    }

    public void markRoundSynced(String sessionId, String roundId) {
        updateRoundSyncStatus(sessionId, roundId, SYNCED, null, LocalDateTime.now());
    }

    public void markRoundSyncFailed(String sessionId, String roundId, String error, boolean lostRisk) {
        updateRoundSyncStatus(
                sessionId,
                roundId,
                lostRisk ? SYNC_LOST_RISK : SYNC_FAILED,
                truncateError(error),
                null
        );
    }

    @PreDestroy
    public void shutdown() {
        sandboxBySession.forEach((sessionId, sandbox) -> {
            try {
                if (sandbox != null && !sandbox.isClosed()) {
                    sandbox.close();
                }
            } catch (Exception e) {
                log.warn("[SessionSandbox] close sandbox failed: sessionId={}, err={}", sessionId, e.getMessage());
            }
        });
        sandboxBySession.clear();
    }

    private <T> T withWorkspaceWriteLock(String sessionId, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try (WorkspaceLock ignored = acquireWorkspaceWriteLock(sessionId)) {
            ensureWorkspaceReady(sessionId);
            return supplier.get();
        }
    }

    private void ensureWorkspaceReady(String sessionId) {
        if (isBlank(sessionId)) {
            return;
        }
        requireSandbox(sessionId);
    }

    private WorkspaceLock acquireWorkspaceReadLock(String sessionId) {
        return acquireWorkspaceLock(sessionId, false);
    }

    private WorkspaceLock acquireWorkspaceWriteLock(String sessionId) {
        return acquireWorkspaceLock(sessionId, true);
    }

    private WorkspaceLock acquireWorkspaceLock(String sessionId, boolean write) {
        if (redissonClient == null || isBlank(sessionId)) {
            return NoopWorkspaceLock.INSTANCE;
        }
        boolean shared = usingSharedSandbox();
        RReadWriteLock rwLock = redissonClient.getReadWriteLock(workspaceLockKey(sessionId));
        // 共享 sandbox 模式下统一使用写锁，避免不同 session 并发切换 workspace。
        RLock lock = (shared || write) ? rwLock.writeLock() : rwLock.readLock();
        lock.lock();
        return () -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("[SessionSandbox] unlock failed: sessionId={}, write={}, err={}",
                        sessionId, write, e.getMessage());
            }
        };
    }

    private RoundBundleSnapshot buildRoundBundleSnapshot(String sessionId, String roundId) {
        FilesystemSandbox sandbox = requireSandbox(sessionId);
        String bundlePath = "/tmp/" + safeToken(roundId) + "-" + Instant.now().toEpochMilli() + ".bundle";
        String commitMessage = "round-" + roundId;
        String command = """
                set -e
                mkdir -p /workspace
                cd /workspace
                if [ ! -d .git ]; then
                  git init >/dev/null 2>&1
                fi
                git config user.email taskforce@sandbox.local
                git config user.name taskforce-bot
                git add -A
                git commit --allow-empty -m %s >/dev/null 2>&1
                commit_hash=$(git rev-parse HEAD)
                git bundle create %s --all >/dev/null 2>&1
                printf "__COMMIT__%%s\\n" "$commit_hash"
                base64 %s
                """.formatted(shellQuote(commitMessage), shellQuote(bundlePath), shellQuote(bundlePath));

        String stdout = runShellChecked(sandbox, command);
        return parseSnapshotOutput(stdout);
    }

    private RoundBundleSnapshot parseSnapshotOutput(String stdout) {
        if (isBlank(stdout)) {
            throw new IllegalStateException("snapshot output is empty");
        }
        String[] lines = stdout.split("\\r?\\n");
        String commitHash = null;
        StringBuilder base64Builder = new StringBuilder();

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (line.startsWith("__COMMIT__")) {
                commitHash = line.substring("__COMMIT__".length()).trim();
                continue;
            }
            if (commitHash != null) {
                base64Builder.append(line.trim());
            }
        }

        if (isBlank(commitHash)) {
            throw new IllegalStateException("snapshot commit hash missing");
        }
        if (base64Builder.isEmpty()) {
            throw new IllegalStateException("snapshot bundle payload missing");
        }
        byte[] bundleBytes = Base64.getDecoder().decode(base64Builder.toString());
        return new RoundBundleSnapshot(commitHash, bundleBytes);
    }

    private void updateRoundSyncStatus(String sessionId, String roundId,
                                       String syncStatus, String syncError, LocalDateTime syncedAt) {
        if (isBlank(sessionId) || isBlank(roundId)) {
            return;
        }
        try {
            toolCallMapper.updateRoundSyncStatus(sessionId, roundId, syncStatus, syncError, syncedAt);
        } catch (Exception e) {
            log.warn("[SessionSandbox] update sync status failed: sessionId={}, roundId={}, status={}, err={}",
                    sessionId, roundId, syncStatus, e.getMessage());
        }
    }

    private String runShellChecked(FilesystemSandbox sandbox, String command) {
        String raw = sandbox.callTool("run_shell_command", Map.of("command", command));
        if (isBlank(raw)) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            boolean isError = node.path("isError").asBoolean(false);
            int returnCode = 0;
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            JsonNode contentNode = node.path("content");
            if (contentNode.isArray()) {
                for (JsonNode item : contentNode) {
                    String description = item.path("description").asText("");
                    String text = item.path("text").asText("");
                    if ("stdout".equalsIgnoreCase(description)) {
                        stdout.append(text);
                    } else if ("stderr".equalsIgnoreCase(description)) {
                        stderr.append(text);
                    } else if ("returncode".equalsIgnoreCase(description)) {
                        try {
                            returnCode = Integer.parseInt(text.trim());
                        } catch (Exception ignored) {
                            returnCode = 1;
                        }
                    }
                }
            }

            if (isError || returnCode != 0) {
                String err = stderr.isEmpty() ? stdout.toString() : stderr.toString();
                throw new IllegalStateException("shell command failed rc=" + returnCode + ", error=" + abbreviate(err, 500));
            }
            return stdout.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse shell response: " + e.getMessage(), e);
        }
    }

    private String applyLineWindow(String content, Integer offset, Integer limit) {
        if (content == null) {
            return null;
        }
        int safeOffset = offset == null || offset < 1 ? 1 : offset;
        int safeLimit = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
        if (safeOffset == 1 && safeLimit == Integer.MAX_VALUE) {
            return content;
        }
        String[] lines = content.split("\\r?\\n", -1);
        int from = Math.max(0, safeOffset - 1);
        if (from >= lines.length) {
            return "";
        }
        int to = Math.min(lines.length, from + safeLimit);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private String extractTextPayload(String raw) {
        if (isBlank(raw)) {
            return raw;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isTextual()) {
                return node.asText();
            }
            JsonNode content = node.get("content");
            if (content != null && content.isTextual()) {
                return content.asText();
            }
            JsonNode result = node.get("result");
            if (result != null && result.isTextual()) {
                return result.asText();
            }
            JsonNode message = node.get("message");
            if (message != null && message.isTextual()) {
                return message.asText();
            }
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    private FilesystemSandbox requireSandbox(String sessionId) {
        String sid = isBlank(sessionId) ? "global" : sessionId;
        if (usingSharedSandbox()) {
            synchronized (this) {
                if (!sid.equals(sharedSandboxActiveSessionId)) {
                    log.info("[SessionSandbox] Switching shared sandbox workspace to session={}", sid);
                    restoreWorkspaceIfPresent(sid, sharedFilesystemSandbox);
                    sharedSandboxActiveSessionId = sid;
                }
            }
            return sharedFilesystemSandbox;
        }
        return sandboxBySession.computeIfAbsent(sid, key -> {
            log.info("[SessionSandbox] Creating FilesystemSandbox for session={}", key);
            FilesystemSandbox sandbox = new FilesystemSandbox(sandboxService, "taskforce", key);
            restoreWorkspaceIfPresent(key, sandbox);
            return sandbox;
        });
    }

    private void restoreWorkspaceIfPresent(String sessionId, FilesystemSandbox sandbox) {
        try {
            runShellChecked(sandbox, "mkdir -p /workspace");
            Optional<RoundRestoreSnapshot> latest = minioRoundSyncService.loadLatestSnapshot(sessionId);
            if (latest.isEmpty()) {
                runShellChecked(sandbox, "find /workspace -mindepth 1 -maxdepth 1 -exec rm -rf {} +");
                log.info("[SessionSandbox] No snapshot found, workspace reset: sessionId={}", sessionId);
                return;
            }

            RoundRestoreSnapshot snapshot = latest.get();
            String token = safeToken(sessionId) + "-" + Instant.now().toEpochMilli();
            String bundlePath = "/tmp/restore-" + token + ".bundle";
            String clonePath = "/tmp/restore-" + token;

            writeBytesToSandboxFile(sandbox, bundlePath, snapshot.bundleBytes());

            String restoreCmd = """
                    set -e
                    mkdir -p /workspace
                    find /workspace -mindepth 1 -maxdepth 1 -exec rm -rf {} +
                    rm -rf %s
                    git clone %s %s >/dev/null 2>&1
                    cp -a %s/. /workspace/
                    rm -rf %s %s
                    """.formatted(
                    shellQuote(clonePath),
                    shellQuote(bundlePath),
                    shellQuote(clonePath),
                    shellQuote(clonePath),
                    shellQuote(clonePath),
                    shellQuote(bundlePath)
            );
            runShellChecked(sandbox, restoreCmd);
            log.info("[SessionSandbox] Restored workspace from snapshot: sessionId={}, roundId={}, commitHash={}",
                    sessionId, snapshot.roundId(), snapshot.commitHash());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to restore workspace from snapshot: sessionId="
                    + sessionId + ", err=" + e.getMessage(), e);
        }
    }

    private void writeBytesToSandboxFile(FilesystemSandbox sandbox, String path, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes cannot be empty");
        }
        String parent = parentDir(path);
        runShellChecked(sandbox, "mkdir -p " + shellQuote(parent) + " && : > " + shellQuote(path));
        for (int offset = 0; offset < bytes.length; offset += BINARY_CHUNK_SIZE) {
            int end = Math.min(bytes.length, offset + BINARY_CHUNK_SIZE);
            byte[] chunk = Arrays.copyOfRange(bytes, offset, end);
            String base64 = Base64.getEncoder().encodeToString(chunk);
            String appendCmd = "printf %s " + shellQuote(base64)
                    + " | base64 -d >> " + shellQuote(path);
            runShellChecked(sandbox, appendCmd);
        }
    }

    private String parentDir(String path) {
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "." : path.substring(0, idx);
    }

    private String normalizePath(String path) {
        if (isBlank(path)) {
            throw new IllegalArgumentException("path cannot be blank");
        }
        return path.trim();
    }

    private String workspaceLockKey(String sessionId) {
        if (usingSharedSandbox()) {
            return "workspace:global";
        }
        return WORKSPACE_LOCK_KEY_PATTERN.formatted(safe(sessionId));
    }

    private boolean usingSharedSandbox() {
        return sharedFilesystemSandbox != null;
    }

    private String safeToken(String value) {
        if (isBlank(value)) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        if (error.length() <= 1000) {
            return error;
        }
        return error.substring(0, 1000);
    }

    private String abbreviate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RoundBundleSnapshot(String commitHash, byte[] bundleBytes) {
    }

    private interface WorkspaceLock extends AutoCloseable {
        @Override
        void close();
    }

    private enum NoopWorkspaceLock implements WorkspaceLock {
        INSTANCE;

        @Override
        public void close() {
            // no-op
        }
    }
}
