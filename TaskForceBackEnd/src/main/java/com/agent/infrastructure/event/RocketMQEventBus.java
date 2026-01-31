package com.agent.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RocketMQEventBus implements EventBus {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.mq.topic:orchestration-events}")
    private String topic;

    // 本地 SSE 订阅者缓存：sessionId -> Sink
    private final Map<String, Sinks.Many<OrchestrationEvent>> subscribers = new ConcurrentHashMap<>();

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
        Sinks.Many<OrchestrationEvent> sink = subscribers.computeIfAbsent(sessionId, id -> {
            log.info("[MQEventBus] New SSE subscriber for sessionId={}", id);
            return Sinks.many().multicast().onBackpressureBuffer(256);
        });

        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("[MQEventBus] SSE subscriber cancelled: sessionId={}", sessionId);
                })
                .doOnTerminate(() -> {
                    log.info("[MQEventBus] SSE subscriber terminated: sessionId={}", sessionId);
                });
    }

    @Override
    public void unsubscribe(String sessionId) {
        Sinks.Many<OrchestrationEvent> sink = subscribers.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            log.info("[MQEventBus] Unsubscribed and cleaned up: sessionId={}", sessionId);
        }
    }

    @Override
    public boolean hasSubscribers(String sessionId) {
        Sinks.Many<OrchestrationEvent> sink = subscribers.get(sessionId);
        return sink != null && sink.currentSubscriberCount() > 0;
    }

    /**
     * 将 MQ 消息推送给本地 SSE 订阅者
     * 由 MQ 消费者调用
     */
    public void pushToSubscriber(String sessionId, OrchestrationEvent event) {
        Sinks.Many<OrchestrationEvent> sink = subscribers.get(sessionId);
        if (sink != null) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isSuccess()) {
                log.debug("[MQEventBus] Pushed to SSE: sessionId={}, type={}", sessionId, event.getEventType());
            } else {
                log.warn("[MQEventBus] Failed to push to SSE: sessionId={}, result={}", sessionId, result);
            }
        } else {
            log.debug("[MQEventBus] No local subscriber for sessionId={}", sessionId);
        }
    }

    /**
     * 获取当前订阅者数量（调试用）
     */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    /**
     * MQ 消息包装类
     */
    public record EventWrapper(String sessionId, String eventType, String eventData) {}
}
