package com.agent.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的事件总线实现
 * 使用 Reactor Sinks 实现 Session 级别的多播
 */
@Slf4j
@Component
public class InMemoryEventBus implements EventBus {

    /**
     * Session -> Sink 映射
     */
    private final ConcurrentHashMap<String, ChannelInfo> channels = new ConcurrentHashMap<>();

    /**
     * 频道存活时间（无订阅者后保留的时间）
     */
    private static final Duration CHANNEL_TTL = Duration.ofMinutes(30);

    /**
     * 最大缓冲区大小
     * 从 256 增大到 8192，支持更长的 LLM 流式输出（约 270 秒）
     */
    private static final int MAX_BUFFER_SIZE = 8192;

    @Override
    public void publish(String sessionId, OrchestrationEvent event) {
        ChannelInfo channelInfo = channels.get(sessionId);
        if (channelInfo != null) {
            Sinks.EmitResult result = channelInfo.sink.tryEmitNext(event);
            if (result.isFailure()) {
                log.warn("[EventBus] Failed to emit event: sessionId={}, eventType={}, result={}",
                        sessionId, event.getEventType(), result);
            } else {
//                log.debug("[EventBus] Event published: sessionId={}, eventType={}",
//                        sessionId, event.getEventType());
            }
            channelInfo.updateLastActivity();
        } else {
            log.debug("[EventBus] No channel for session: {}, event dropped", sessionId);
        }
    }

    @Override
    public Flux<OrchestrationEvent> subscribe(String sessionId) {
        ChannelInfo channelInfo = channels.computeIfAbsent(sessionId, k -> {
            log.info("[EventBus] Creating new channel for session: {}", sessionId);
            Sinks.Many<OrchestrationEvent> sink = Sinks.many()
                    .replay()
                    .limit(MAX_BUFFER_SIZE);  // 保留最近 8192 个事件，支持重连恢复
            return new ChannelInfo(sink);
        });

        channelInfo.incrementSubscribers();
        channelInfo.updateLastActivity();

        return channelInfo.sink.asFlux()
                .doOnCancel(() -> {
                    channelInfo.decrementSubscribers();
                    log.info("[EventBus] Subscriber cancelled: sessionId={}, remaining={}",
                            sessionId, channelInfo.getSubscriberCount());
                })
                .doOnError(e -> {
                    channelInfo.decrementSubscribers();
                    // 不记录 AsyncContext 错误，这是正常断开
                    if (!(e instanceof IllegalStateException) ||
                        !e.getMessage().contains("AsyncContext")) {
                        log.info("[EventBus] Subscriber error: sessionId={}, remaining={}, error={}",
                                sessionId, channelInfo.getSubscriberCount(), e.getMessage());
                    }
                })
                .doOnComplete(() -> {
                    channelInfo.decrementSubscribers();
                    log.info("[EventBus] Subscriber completed: sessionId={}, remaining={}",
                            sessionId, channelInfo.getSubscriberCount());
                })
                .onErrorResume(e -> {
                    // 捕获所有错误并返回空 Flux，防止错误传播到 AsyncContext
                    log.debug("[EventBus] Error handled gracefully for session: {}", sessionId);
                    return Flux.empty();
                });
    }

    @Override
    public void unsubscribe(String sessionId) {
        ChannelInfo removed = channels.remove(sessionId);
        if (removed != null) {
            removed.sink.tryEmitComplete();
            log.info("[EventBus] Channel closed for session: {}", sessionId);
        }
    }

    @Override
    public boolean hasSubscribers(String sessionId) {
        ChannelInfo channelInfo = channels.get(sessionId);
        return channelInfo != null && channelInfo.getSubscriberCount() > 0;
    }

    /**
     * 定时清理过期的空闲频道
     */
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public void cleanupIdleChannels() {
        Instant now = Instant.now();
        channels.entrySet().removeIf(entry -> {
            ChannelInfo info = entry.getValue();
            boolean isIdle = info.getSubscriberCount() == 0;
            boolean isExpired = Duration.between(info.getLastActivity(), now).compareTo(CHANNEL_TTL) > 0;

            if (isIdle && isExpired) {
                info.sink.tryEmitComplete();
                log.info("[EventBus] Cleaned up idle channel: sessionId={}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 获取当前活跃频道数（用于监控）
     */
    public int getActiveChannelCount() {
        return channels.size();
    }

    /**
     * 频道信息
     */
    private static class ChannelInfo {
        final Sinks.Many<OrchestrationEvent> sink;
        private volatile int subscriberCount = 0;
        private volatile Instant lastActivity = Instant.now();

        ChannelInfo(Sinks.Many<OrchestrationEvent> sink) {
            this.sink = sink;
        }

        synchronized void incrementSubscribers() {
            subscriberCount++;
        }

        synchronized void decrementSubscribers() {
            if (subscriberCount > 0) {
                subscriberCount--;
            }
        }

        int getSubscriberCount() {
            return subscriberCount;
        }

        void updateLastActivity() {
            lastActivity = Instant.now();
        }

        Instant getLastActivity() {
            return lastActivity;
        }
    }
}
