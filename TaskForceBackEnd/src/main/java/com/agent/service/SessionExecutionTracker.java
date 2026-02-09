package com.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/**
 * 会话执行跟踪器
 * 用于跟踪每个会话的所有可取消任务（Disposable、Future），提供批量取消能力
 */
@Slf4j
@Service
public class SessionExecutionTracker {

    // sessionId -> List<Disposable>
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Disposable>> disposables = new ConcurrentHashMap<>();

    // sessionId -> List<Future<?>>
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Future<?>>> futures = new ConcurrentHashMap<>();

    /**
     * 注册一个 Disposable 到指定会话
     */
    public void registerDisposable(String sessionId, Disposable disposable) {
        if (sessionId == null || disposable == null) {
            return;
        }
        disposables.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(disposable);
        log.debug("[ExecutionTracker] Registered disposable for session: {}", sessionId);
    }

    /**
     * 注册一个 Future 到指定会话
     */
    public void registerFuture(String sessionId, Future<?> future) {
        if (sessionId == null || future == null) {
            return;
        }
        futures.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(future);
        log.debug("[ExecutionTracker] Registered future for session: {}", sessionId);
    }

    /**
     * 取消指定会话的所有任务
     */
    public void cancelAll(String sessionId) {
        if (sessionId == null) {
            return;
        }

        int cancelledCount = 0;

        // 取消所有 Disposable
        CopyOnWriteArrayList<Disposable> sessionDisposables = disposables.get(sessionId);
        if (sessionDisposables != null) {
            for (Disposable disposable : sessionDisposables) {
                if (!disposable.isDisposed()) {
                    try {
                        disposable.dispose();
                        cancelledCount++;
                    } catch (Exception e) {
                        log.warn("[ExecutionTracker] Failed to dispose: {}", e.getMessage());
                    }
                }
            }
        }

        // 取消所有 Future
        CopyOnWriteArrayList<Future<?>> sessionFutures = futures.get(sessionId);
        if (sessionFutures != null) {
            for (Future<?> future : sessionFutures) {
                if (!future.isDone() && !future.isCancelled()) {
                    try {
                        future.cancel(true);
                        cancelledCount++;
                    } catch (Exception e) {
                        log.warn("[ExecutionTracker] Failed to cancel future: {}", e.getMessage());
                    }
                }
            }
        }

        log.info("[ExecutionTracker] Cancelled {} tasks for session: {}", cancelledCount, sessionId);
    }

    /**
     * 清理指定会话的已完成任务
     */
    public void cleanup(String sessionId) {
        if (sessionId == null) {
            return;
        }

        // 清理已完成的 Disposable
        CopyOnWriteArrayList<Disposable> sessionDisposables = disposables.get(sessionId);
        if (sessionDisposables != null) {
            sessionDisposables.removeIf(Disposable::isDisposed);
            if (sessionDisposables.isEmpty()) {
                disposables.remove(sessionId);
            }
        }

        // 清理已完成的 Future
        CopyOnWriteArrayList<Future<?>> sessionFutures = futures.get(sessionId);
        if (sessionFutures != null) {
            sessionFutures.removeIf(future -> future.isDone() || future.isCancelled());
            if (sessionFutures.isEmpty()) {
                futures.remove(sessionId);
            }
        }

        log.debug("[ExecutionTracker] Cleaned up session: {}", sessionId);
    }

    /**
     * 清理所有会话的已完成任务
     */
    public void cleanupAll() {
        disposables.keySet().forEach(this::cleanup);
        log.debug("[ExecutionTracker] Cleaned up all sessions");
    }
}
