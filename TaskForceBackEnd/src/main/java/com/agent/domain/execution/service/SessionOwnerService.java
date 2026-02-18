package com.agent.domain.execution.service;

import com.agent.infrastructure.event.events.SessionCompleteEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Session owner registry for Team mode.
 * Owner node is stored as host:port, and guarded by a Redisson lock.
 */
@Slf4j
@Service
public class SessionOwnerService {

    private static final String OWNER_KEY_PREFIX = "team:owner:";
    private static final String OWNER_LOCK_PREFIX = "team:owner:lock:";
    private static final Duration OWNER_TTL = Duration.ofSeconds(40);
    private static final long LOCK_WAIT_SECONDS = 5L;
    private static final long RENEW_INTERVAL_SECONDS = 10L;

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final String nodeId;

    private final ScheduledExecutorService renewScheduler;
    private final Map<String, ScheduledFuture<?>> renewTasks = new ConcurrentHashMap<>();

    public SessionOwnerService(RedissonClient redissonClient,
                               StringRedisTemplate redisTemplate,
                               @Value("${cluster.node-id:${HOSTNAME:localhost}:${server.port:8080}}")
                               String nodeId) {
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.nodeId = nodeId;
        this.renewScheduler = Executors.newSingleThreadScheduledExecutor(new OwnerRenewThreadFactory());
    }

    public String nodeId() {
        return nodeId;
    }

    public boolean tryAcquireOwner(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        if (isCurrentNodeOwner(sessionId)) {
            refreshOwnerKey(sessionId);
            ensureRenewTask(sessionId);
            return true;
        }

        RLock lock = redissonClient.getLock(ownerLockKey(sessionId));
        boolean locked;
        try {
            // leaseTime 不传，使用 Redisson watchdog 自动续约。
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("[SessionOwner] tryAcquireOwner lock failed: sessionId={}, err={}", sessionId, e.getMessage());
            return false;
        }

        if (!locked) {
            return false;
        }

        try {
            String ownerNode = redisTemplate.opsForValue().get(ownerKey(sessionId));
            if (ownerNode != null && !ownerNode.isBlank() && !isCurrentNode(ownerNode)) {
                // 已有其他节点 owner，放弃本次抢占。
                safeUnlock(lock);
                return false;
            }

            refreshOwnerKey(sessionId);
            ensureRenewTask(sessionId);
            log.info("[SessionOwner] Owner acquired: sessionId={}, owner={}", sessionId, nodeId);
            return true;
        } catch (Exception e) {
            safeUnlock(lock);
            log.warn("[SessionOwner] acquire owner failed after locking: sessionId={}, err={}", sessionId, e.getMessage());
            return false;
        }
    }

    public Optional<String> getOwnerNode(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String ownerNode = redisTemplate.opsForValue().get(ownerKey(sessionId));
        if (ownerNode == null || ownerNode.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ownerNode);
    }

    public boolean isCurrentNodeOwner(String sessionId) {
        return getOwnerNode(sessionId).map(this::isCurrentNode).orElse(false);
    }

    public boolean isCurrentNode(String ownerNodeId) {
        return ownerNodeId != null && ownerNodeId.equals(nodeId);
    }

    public void releaseOwner(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        cancelRenewTask(sessionId);

        String currentOwner = redisTemplate.opsForValue().get(ownerKey(sessionId));
        if (currentOwner != null && !currentOwner.isBlank() && !isCurrentNode(currentOwner)) {
            return;
        }
        redisTemplate.delete(ownerKey(sessionId));

        try {
            redissonClient.getLock(ownerLockKey(sessionId)).forceUnlock();
        } catch (Exception e) {
            log.debug("[SessionOwner] forceUnlock ignored: sessionId={}, err={}", sessionId, e.getMessage());
        }
        log.info("[SessionOwner] Owner released: sessionId={}, owner={}", sessionId, nodeId);
    }

    @EventListener
    public void onSessionComplete(SessionCompleteEvent event) {
        if (event == null || event.getSessionId() == null) {
            return;
        }
        if (isCurrentNodeOwner(event.getSessionId())) {
            releaseOwner(event.getSessionId());
        }
    }

    @PreDestroy
    public void shutdown() {
        renewTasks.keySet().forEach(this::releaseOwner);
        renewScheduler.shutdownNow();
    }

    private void ensureRenewTask(String sessionId) {
        renewTasks.computeIfAbsent(sessionId, key -> renewScheduler.scheduleAtFixedRate(
                () -> refreshOwnerKeyIfStillOwned(key),
                RENEW_INTERVAL_SECONDS,
                RENEW_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        ));
    }

    private void cancelRenewTask(String sessionId) {
        ScheduledFuture<?> task = renewTasks.remove(sessionId);
        if (task != null) {
            task.cancel(true);
        }
    }

    private void refreshOwnerKeyIfStillOwned(String sessionId) {
        try {
            String ownerNode = redisTemplate.opsForValue().get(ownerKey(sessionId));
            if (ownerNode == null || ownerNode.isBlank() || isCurrentNode(ownerNode)) {
                refreshOwnerKey(sessionId);
            }
        } catch (Exception e) {
            log.warn("[SessionOwner] renew owner key failed: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    private void refreshOwnerKey(String sessionId) {
        redisTemplate.opsForValue().set(ownerKey(sessionId), nodeId, OWNER_TTL);
    }

    private String ownerKey(String sessionId) {
        return OWNER_KEY_PREFIX + sessionId;
    }

    private String ownerLockKey(String sessionId) {
        return OWNER_LOCK_PREFIX + sessionId;
    }

    private void safeUnlock(RLock lock) {
        try {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception ignore) {
            // ignore unlock failure
        }
    }

    private static class OwnerRenewThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "session-owner-renew");
            t.setDaemon(true);
            return t;
        }
    }
}
