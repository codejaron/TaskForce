package com.agent.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RedisStreamEventBus implements EventBus {

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private static final String STREAM_KEY_PREFIX = "sse:stream:";
    private static final String CHANNEL_PREFIX = "sse:notify:";
    private static final String WORKER_STREAM_KEY_PREFIX = "sse:worker:stream:";
    private static final String WORKER_CHANNEL_PREFIX = "sse:worker:notify:";
    private static final Duration STREAM_TTL = Duration.ofHours(1);
    private static final long STREAM_MAX_LEN = 10000;

    private final Map<String, SubscriptionContext> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionContext> workerSubscriptions = new ConcurrentHashMap<>();

    public RedisStreamEventBus(StringRedisTemplate redisTemplate,
                               RedisMessageListenerContainer listenerContainer,
                               ObjectMapper objectMapper,
                               ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publish(String sessionId, OrchestrationEvent event) {
        String streamKey = STREAM_KEY_PREFIX + sessionId;
        String channel = CHANNEL_PREFIX + sessionId;

        try {
            Map<String, String> eventMap = new HashMap<>();
            eventMap.put("eventType", event.getEventType());
            eventMap.put("eventData", objectMapper.writeValueAsString(event));
            eventMap.put("eventClass", event.getClass().getName());

            StringRecord record = StringRecord.of(eventMap).withStreamKey(streamKey);
            RecordId recordId = redisTemplate.opsForStream().add(record);

            redisTemplate.opsForStream().trim(streamKey, STREAM_MAX_LEN, true);
            redisTemplate.expire(streamKey, STREAM_TTL);

            // Pub/Sub 通知
            redisTemplate.convertAndSend(channel, recordId.getValue());
            
            // 发布 Spring 本地事件，供 @EventListener 监听
            eventPublisher.publishEvent(event);

//            log.debug("[RedisStreamEventBus] Published: sessionId={}, type={}, recordId={}",
//                    sessionId, event.getEventType(), recordId.getValue());

        } catch (Exception e) {
            log.error("[RedisStreamEventBus] Publish failed: sessionId={}", sessionId, e);
        }
    }

    @Override
    public Flux<OrchestrationEvent> subscribe(String sessionId) {
        return subscribe(sessionId, null);
    }

    public Flux<OrchestrationEvent> subscribe(String sessionId, String lastEventId) {
        String streamKey = STREAM_KEY_PREFIX + sessionId;
        String channel = CHANNEL_PREFIX + sessionId;

        // 关闭旧的订阅
        SubscriptionContext oldCtx = subscriptions.get(sessionId);
        if (oldCtx != null) {
            cleanup(sessionId, oldCtx);
        }

        // 创建新的订阅上下文
        SubscriptionContext ctx = new SubscriptionContext();
        ctx.lastReadId = (lastEventId != null && !lastEventId.isEmpty()) ? lastEventId : "0";
        ctx.channel = channel;
        subscriptions.put(sessionId, ctx);

        // 1. 读取历史消息（断点续传）
        readAndEmit(streamKey, ctx);

        // 2. 创建 MessageListener 并保存引用
        MessageListener listener = (message, pattern) -> {
           //log.debug("[RedisStreamEventBus] Pub/Sub notification: sessionId={}", sessionId);
            readAndEmit(streamKey, ctx);
        };
        ctx.listener = listener;

        // 3. 订阅 Pub/Sub
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));

        log.info("[RedisStreamEventBus] Subscribed: sessionId={}, lastEventId={}", sessionId, lastEventId);

        return ctx.sink.asFlux()
                .doOnCancel(() -> {
                    log.info("[RedisStreamEventBus] SSE cancelled: sessionId={}", sessionId);
                    cleanup(sessionId, ctx);
                })
                .doOnTerminate(() -> {
                    log.info("[RedisStreamEventBus] SSE terminated: sessionId={}", sessionId);
                });
    }

    /**
     * 读取消息并发送，同时更新 lastReadId
     */
    private void readAndEmit(String streamKey, SubscriptionContext ctx) {
        try {
            // 读取 lastReadId 之后的所有消息
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .range(streamKey, Range.rightOpen(ctx.lastReadId, "+"));

            if (records != null && !records.isEmpty()) {
                for (MapRecord<String, Object, Object> record : records) {
                    String recordId = record.getId().getValue();

                    // 跳过已读的（Range.rightOpen 应该已经排除了，但保险起见）
                    if (recordId.compareTo(ctx.lastReadId) <= 0) {
                        continue;
                    }

                    OrchestrationEvent event = parseEvent(record);
                    if (event != null) {
                        Sinks.EmitResult result = ctx.sink.tryEmitNext(event);
                        if (result.isFailure()) {
                            log.warn("[RedisStreamEventBus] Emit failed: result={}", result);
                            break;
                        }
                    }

                    // 更新 lastReadId
                    ctx.lastReadId = recordId;
                }
//                log.debug("[RedisStreamEventBus] Emitted {} events, lastReadId={}",
//                        records.size(), ctx.lastReadId);
            }
        } catch (Exception e) {
            log.error("[RedisStreamEventBus] Read and emit failed: streamKey={}", streamKey, e);
        }
    }

    private OrchestrationEvent parseEvent(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> map = record.getValue();
            String eventClass = (String) map.get("eventClass");
            String eventData = (String) map.get("eventData");

            Class<?> clazz = Class.forName(eventClass);
            OrchestrationEvent event = (OrchestrationEvent) objectMapper.readValue(eventData, clazz);

            // 把 Redis Stream 的 RecordId 存到事件里，用于 SSE 的 id 字段
            event.setStreamRecordId(record.getId().getValue());

            return event;
        } catch (Exception e) {
            log.error("[RedisStreamEventBus] Parse event failed: recordId={}", record.getId(), e);
            return null;
        }
    }

    private void cleanup(String sessionId, SubscriptionContext ctx) {
        // 移除 MessageListener
        if (ctx.listener != null && ctx.channel != null) {
            listenerContainer.removeMessageListener(ctx.listener, new ChannelTopic(ctx.channel));
        }
        ctx.close();
        subscriptions.remove(sessionId, ctx);  // 只移除匹配的
        log.info("[RedisStreamEventBus] Cleaned up: sessionId={}", sessionId);
    }

    @Override
    public void unsubscribe(String sessionId) {
        SubscriptionContext ctx = subscriptions.remove(sessionId);
        if (ctx != null) {
            cleanup(sessionId, ctx);
        }
    }

    @Override
    public boolean hasSubscribers(String sessionId) {
        SubscriptionContext ctx = subscriptions.get(sessionId);
        return ctx != null && ctx.sink.currentSubscriberCount() > 0;
    }

    @Override
    public Flux<OrchestrationEvent> subscribeWorker(String sessionId, String instanceId) {
        return subscribeWorker(sessionId, instanceId, null);
    }

    public Flux<OrchestrationEvent> subscribeWorker(String sessionId, String instanceId, String lastEventId) {
        String workerKey = sessionId + ":" + instanceId;
        String streamKey = WORKER_STREAM_KEY_PREFIX + workerKey;
        String channel = WORKER_CHANNEL_PREFIX + workerKey;

        // 关闭旧的订阅
        SubscriptionContext oldCtx = workerSubscriptions.get(workerKey);
        if (oldCtx != null) {
            cleanupWorker(workerKey, oldCtx);
        }

        // 创建新的订阅上下文
        SubscriptionContext ctx = new SubscriptionContext();
        ctx.lastReadId = (lastEventId != null && !lastEventId.isEmpty()) ? lastEventId : "0";
        ctx.channel = channel;
        workerSubscriptions.put(workerKey, ctx);

        // 1. 读取历史消息
        readAndEmit(streamKey, ctx);

        // 2. 创建 MessageListener
        MessageListener listener = (message, pattern) -> {
            readAndEmit(streamKey, ctx);
        };
        ctx.listener = listener;

        // 3. 订阅 Pub/Sub
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));

        log.info("[RedisStreamEventBus] Subscribed to worker: sessionId={}, instanceId={}, lastEventId={}",
                sessionId, instanceId, lastEventId);

        return ctx.sink.asFlux()
                .doOnCancel(() -> {
                    log.info("[RedisStreamEventBus] Worker SSE cancelled: sessionId={}, instanceId={}",
                            sessionId, instanceId);
                    cleanupWorker(workerKey, ctx);
                })
                .doOnTerminate(() -> {
                    log.info("[RedisStreamEventBus] Worker SSE terminated: sessionId={}, instanceId={}",
                            sessionId, instanceId);
                });
    }

    @Override
    public void publishToWorker(String sessionId, String instanceId, OrchestrationEvent event) {
        String workerKey = sessionId + ":" + instanceId;
        String streamKey = WORKER_STREAM_KEY_PREFIX + workerKey;
        String channel = WORKER_CHANNEL_PREFIX + workerKey;

        try {
            Map<String, String> eventMap = new HashMap<>();
            eventMap.put("eventType", event.getEventType());
            eventMap.put("eventData", objectMapper.writeValueAsString(event));
            eventMap.put("eventClass", event.getClass().getName());

            StringRecord record = StringRecord.of(eventMap).withStreamKey(streamKey);
            RecordId recordId = redisTemplate.opsForStream().add(record);

            redisTemplate.opsForStream().trim(streamKey, STREAM_MAX_LEN, true);
            redisTemplate.expire(streamKey, STREAM_TTL);

            // Pub/Sub 通知
            redisTemplate.convertAndSend(channel, recordId.getValue());

//            log.debug("[RedisStreamEventBus] Published to worker: sessionId={}, instanceId={}, type={}",
//                    sessionId, instanceId, event.getEventType());

        } catch (Exception e) {
            log.error("[RedisStreamEventBus] Publish to worker failed: sessionId={}, instanceId={}",
                    sessionId, instanceId, e);
        }
    }

    private void cleanupWorker(String workerKey, SubscriptionContext ctx) {
        // 移除 MessageListener
        if (ctx.listener != null && ctx.channel != null) {
            listenerContainer.removeMessageListener(ctx.listener, new ChannelTopic(ctx.channel));
        }
        ctx.close();
        workerSubscriptions.remove(workerKey, ctx);
        log.info("[RedisStreamEventBus] Cleaned up worker subscription: workerKey={}", workerKey);
    }

    private static class SubscriptionContext {
        final Sinks.Many<OrchestrationEvent> sink = Sinks.many().multicast().onBackpressureBuffer(1024);
        volatile String lastReadId = "0";
        volatile String channel;
        volatile MessageListener listener;

        void close() {
            sink.tryEmitComplete();
        }
    }
}
