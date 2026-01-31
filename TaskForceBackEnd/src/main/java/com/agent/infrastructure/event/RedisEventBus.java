package com.agent.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
//@Component
public class RedisEventBus implements EventBus {

    private static final String CHANNEL_PREFIX = "sse:session:";
    private static final Duration CHANNEL_TTL = Duration.ofMinutes(30);
    private static final int MAX_BUFFER_SIZE = 8192;

    private final ReactiveStringRedisTemplate reactiveRedisTemplate;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, LocalSubscription> subscriptions = new ConcurrentHashMap<>();

    public RedisEventBus(ReactiveStringRedisTemplate reactiveRedisTemplate,
                         ObjectMapper objectMapper) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.objectMapper = objectMapper;
        log.info("[RedisEventBus] Initialized with ReactiveRedisTemplate");
    }

    @Override
    public void publish(String sessionId, OrchestrationEvent event) {
        String channel = CHANNEL_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(new EventWrapper(
                    event.getClass().getName(),
                    objectMapper.writeValueAsString(event)
            ));

            reactiveRedisTemplate.convertAndSend(channel, json)
//                    .doOnSuccess(receivers -> log.debug("[RedisEventBus] Published: sessionId={}, type={}, receivers={}",
//                            sessionId, event.getEventType(), receivers))
                    .doOnError(e -> log.error("[RedisEventBus] Publish failed: sessionId={}", sessionId, e))
                    .subscribe();

        } catch (JsonProcessingException e) {
            log.error("[RedisEventBus] Serialize failed: sessionId={}", sessionId, e);
        }
    }

    @Override
    public Flux<OrchestrationEvent> subscribe(String sessionId) {
        LocalSubscription subscription = subscriptions.computeIfAbsent(sessionId, this::createSubscription);
        subscription.incrementSubscribers();
        subscription.updateLastActivity();

        log.info("[RedisEventBus] Client subscribed: sessionId={}, totalSubscribers={}",
                sessionId, subscription.subscriberCount);

        return subscription.sink.asFlux()
                .doOnCancel(() -> handleDisconnect(sessionId, subscription))
                .doOnError(e -> handleDisconnect(sessionId, subscription))
                .doOnComplete(() -> handleDisconnect(sessionId, subscription))
                .onErrorResume(e -> {
                    log.debug("[RedisEventBus] Error handled: sessionId={}", sessionId);
                    return Flux.empty();
                });
    }

    @Override
    public void unsubscribe(String sessionId) {
        LocalSubscription subscription = subscriptions.remove(sessionId);
        if (subscription != null) {
            subscription.close();
            log.info("[RedisEventBus] Unsubscribed: sessionId={}", sessionId);
        }
    }

    @Override
    public boolean hasSubscribers(String sessionId) {
        LocalSubscription subscription = subscriptions.get(sessionId);
        return subscription != null && subscription.subscriberCount > 0;
    }

    private LocalSubscription createSubscription(String sessionId) {
        String channel = CHANNEL_PREFIX + sessionId;
        log.info("[RedisEventBus] Creating Redis subscription: sessionId={}, channel={}", sessionId, channel);

        // replay 保留最近 8192条，溢出自动丢弃最旧的
        Sinks.Many<OrchestrationEvent> sink = Sinks.many()
                .replay()
                .limit(MAX_BUFFER_SIZE);

        Disposable disposable = reactiveRedisTemplate
                .listenToChannel(channel)
                .doOnSubscribe(s -> log.info("[RedisEventBus] Redis SUBSCRIBE started: channel={}", channel))
                .doOnNext(message -> {
                    try {
                        String json = message.getMessage();
                        EventWrapper wrapper = objectMapper.readValue(json, EventWrapper.class);
                        Class<?> eventClass = Class.forName(wrapper.eventClass);
                        OrchestrationEvent event = (OrchestrationEvent) objectMapper.readValue(wrapper.eventJson, eventClass);

                        Sinks.EmitResult result = sink.tryEmitNext(event);
                        if (result.isFailure()) {
                            log.debug("[RedisEventBus] Emit result: sessionId={}, result={}", sessionId, result);
                        }
                    } catch (Exception e) {
                        log.error("[RedisEventBus] Process message failed: sessionId={}", sessionId, e);
                    }
                })
                .doOnError(e -> log.error("[RedisEventBus] Redis subscription error: channel={}", channel, e))
                .doOnCancel(() -> log.info("[RedisEventBus] Redis subscription cancelled: channel={}", channel))
                .subscribe();

        return new LocalSubscription(sink, disposable, channel);
    }


    private void handleDisconnect(String sessionId, LocalSubscription subscription) {
        subscription.decrementSubscribers();
        log.debug("[RedisEventBus] Client disconnected: sessionId={}, remaining={}", sessionId, subscription.subscriberCount);
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupIdleSubscriptions() {
        Instant now = Instant.now();
        subscriptions.entrySet().removeIf(entry -> {
            LocalSubscription sub = entry.getValue();
            if (sub.subscriberCount == 0 && Duration.between(sub.lastActivity, now).compareTo(CHANNEL_TTL) > 0) {
                sub.close();
                log.info("[RedisEventBus] Cleaned idle subscription: sessionId={}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    public int getActiveSubscriptionCount() {
        return subscriptions.size();
    }

    @PreDestroy
    public void cleanup() {
        log.info("[RedisEventBus] Cleanup {} subscriptions", subscriptions.size());
        subscriptions.forEach((id, sub) -> sub.close());
        subscriptions.clear();
    }

    // === 内部类 ===

    private record EventWrapper(String eventClass, String eventJson) {}

    private static class LocalSubscription {
        final Sinks.Many<OrchestrationEvent> sink;
        final Disposable redisSubscription;
        final String channel;
        volatile int subscriberCount = 0;
        volatile Instant lastActivity = Instant.now();

        LocalSubscription(Sinks.Many<OrchestrationEvent> sink, Disposable redisSubscription, String channel) {
            this.sink = sink;
            this.redisSubscription = redisSubscription;
            this.channel = channel;
        }

        synchronized void incrementSubscribers() { subscriberCount++; }
        synchronized void decrementSubscribers() { if (subscriberCount > 0) subscriberCount--; }
        void updateLastActivity() { lastActivity = Instant.now(); }

        void close() {
            if (redisSubscription != null && !redisSubscription.isDisposed()) {
                redisSubscription.dispose();
            }
            sink.tryEmitComplete();
        }
    }
}