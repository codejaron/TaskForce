package com.agent.infrastructure.mq;

import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.ErrorEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 死信队列消费者
 *
 * 处理多次重试仍失败的消息：
 * 1. 记录详细日志（告警）
 * 2. 通过 EventBus 通知前端任务失败
 *
 * 死信队列命名规则：%DLQ%{consumerGroup}
 * 本项目为：%DLQ%taskforce-consumer
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%taskforce-consumer",
        consumerGroup = "taskforce-dlq-consumer"
)
public class DlqConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final EventBus eventBus;

    @Override
    public void onMessage(String message) {
        log.error("[DLQ-Consumer] ========== DEAD LETTER MESSAGE ==========");
        log.error("[DLQ-Consumer] Raw message: {}", message);

        try {
            TaskMessage taskMessage = objectMapper.readValue(message, TaskMessage.class);

            log.error("[DLQ-Consumer] Dead letter details: sessionId={}, requestId={}, type={}, timestamp={}",
                    taskMessage.getSessionId(),
                    taskMessage.getRequestId(),
                    taskMessage.getType(),
                    taskMessage.getTimestamp());

            // 通知前端任务彻底失败
            String errorMessage = "任务处理失败，已达最大重试次数。请稍后重试或联系管理员。";
            eventBus.publish(taskMessage.getSessionId(),
                    new ErrorEvent(taskMessage.getSessionId(), errorMessage));

            // TODO: 可以在这里添加告警通知（钉钉、邮件等）
            // alertService.sendAlert("任务处理失败", taskMessage);

        } catch (Exception e) {
            log.error("[DLQ-Consumer] Failed to parse dead letter message", e);
        }

        log.error("[DLQ-Consumer] ==========================================");
    }
}

