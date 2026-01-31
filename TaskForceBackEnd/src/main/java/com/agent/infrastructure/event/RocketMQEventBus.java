package com.agent.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RocketMQEventBus implements EventBus {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.mq.topic:orchestration-events}")
    private String topic;

    // 本地 SSE 订阅者缓存：sessionId -> SinkWrapper
    private final Map<String, SinkWrapper> subscribers = new ConcurrentHashMap<>();

    // Sink 过期时间（30分钟无订阅者则清理）
    private static final Duration SINK_TTL = Duration.ofMinutes(30);

    public RocketMQEventBus(RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String sessionId, OrchestrationEvent event) {
        try {
            EventWrapper wrapper = new EventWrapper(sessionId, event.getEventType(), objectMapper.writeValueAsString(event));
            String message = objectMapper.writeValueAsString(wrapper);

            rocketMQTemplate.convertAndSend(topic, message);

        } catch (JsonProcessingException e) {
            log.error("[MQEventBus] Failed to serialize event: sessionId={}, type={}", sessionId, event.getEventType(), e);
        }
    }

    @Override
    public Flux<OrchestrationEvent> subscribe(String sessionId) {
        SinkWrapper wrapper = subscribers.computeIfAbsent(sessionId, id -> {
            log.info("[MQEventBus] New SSE subscriber for sessionId={}", id);
            // 使用 replay() 缓存最近的事件，防止订阅晚于发布导致丢失
            Sinks.Many<OrchestrationEvent> sink = Sinks.many().replay().limit(100);
            return new SinkWrapper(sink);
        });

        wrapper.updateLastActivity();

        return wrapper.sink.asFlux()
                .doOnCancel(() -> {
                    log.info("[MQEventBus] SSE subscriber cancelled: sessionId={}", sessionId);
                })
                .doOnTerminate(() -> {
                    log.info("[MQEventBus] SSE subscriber terminated: sessionId={}", sessionId);
                });
    }

    @Override
    public void unsubscribe(String sessionId) {
        SinkWrapper wrapper = subscribers.remove(sessionId);
        if (wrapper != null) {
            wrapper.sink.tryEmitComplete();
            log.info("[MQEventBus] Unsubscribed and cleaned up: sessionId={}", sessionId);
        }
    }

    @Override
    public boolean hasSubscribers(String sessionId) {
        SinkWrapper wrapper = subscribers.get(sessionId);
        return wrapper != null && wrapper.sink.currentSubscriberCount() > 0;
    }

    /**
     * 将 MQ 消息推送给本地 SSE 订阅者
     * 由 MQ 消费者调用
     *
     * 如果订阅者还未连接，会创建 sink 并缓存事件（使用 replay）
     */
    public void pushToSubscriber(String sessionId, OrchestrationEvent event) {
        // 如果订阅者不存在，创建一个带 replay 的 sink 来缓存事件
        SinkWrapper wrapper = subscribers.computeIfAbsent(sessionId, id -> {
            log.info("[MQEventBus] Creating sink for sessionId={} (no subscriber yet, will cache events)", id);
            Sinks.Many<OrchestrationEvent> sink = Sinks.many().replay().limit(8192);
            return new SinkWrapper(sink);
        });

        wrapper.updateLastActivity();

        Sinks.EmitResult result = wrapper.sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("[MQEventBus] Failed to push to sink: sessionId={}, result={}", sessionId, result);
        }
    }

    /**
     * 定期清理空闲的 Sink（30分钟无活动且无订阅者）
     */
    @Scheduled(fixedRate = 60000)  // 每分钟检查一次
    public void cleanupIdleSinks() {
        Instant now = Instant.now();
        subscribers.entrySet().removeIf(entry -> {
            SinkWrapper wrapper = entry.getValue();
            boolean isIdle = wrapper.sink.currentSubscriberCount() == 0 &&
                    Duration.between(wrapper.lastActivity, now).compareTo(SINK_TTL) > 0;
            if (isIdle) {
                wrapper.sink.tryEmitComplete();
                log.info("[MQEventBus] Cleaned idle sink: sessionId={}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 获取当前订阅者数量（调试用）
     */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    @PreDestroy
    public void cleanup() {
        log.info("[MQEventBus] Cleanup {} sinks", subscribers.size());
        subscribers.forEach((id, wrapper) -> wrapper.sink.tryEmitComplete());
        subscribers.clear();
    }

    /**
     * MQ 消息包装类
     */
    public record EventWrapper(String sessionId, String eventType, String eventData) {}

    /**
     * Sink 包装类，包含最后活动时间
     */
    private static class SinkWrapper {
        final Sinks.Many<OrchestrationEvent> sink;
        volatile Instant lastActivity;

        SinkWrapper(Sinks.Many<OrchestrationEvent> sink) {
            this.sink = sink;
            this.lastActivity = Instant.now();
        }

        void updateLastActivity() {
            this.lastActivity = Instant.now();
        }
    }
}
