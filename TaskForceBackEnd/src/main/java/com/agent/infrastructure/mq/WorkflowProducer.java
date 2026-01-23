package com.agent.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 工作流消息生产者
 * 负责将任务消息发送到 RocketMQ
 *
 * 关键设计：
 * 1. 使用 syncSendOrderly 同步顺序发送
 * 2. 用 sessionId 作为 hashKey，保证同一 session 的消息发到同一 Queue
 * 3. 发送失败抛出异常，由 Controller 层处理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Topic 名称
     */
    public static final String TOPIC = "taskforce-workflow";

    /**
     * 发送任务消息
     * 使用 sessionId 作为 hashKey 保证顺序
     *
     * @param message 任务消息
     * @throws RuntimeException 发送失败时抛出
     */
    public void send(TaskMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("[MQ-Producer] Failed to serialize message: sessionId={}, requestId={}",
                    message.getSessionId(), message.getRequestId(), e);
            throw new RuntimeException("消息序列化失败", e);
        }

        log.info("[MQ-Producer] Sending message: sessionId={}, requestId={}, type={}",
                message.getSessionId(), message.getRequestId(), message.getType());

        try {
            // 使用 syncSendOrderly 保证同一 sessionId 的消息顺序
            // hashKey = sessionId，相同 sessionId 会被路由到同一 Queue
            SendResult result = rocketMQTemplate.syncSendOrderly(
                    TOPIC,
                    MessageBuilder.withPayload(json).build(),
                    message.getSessionId()  // hashKey
            );

            if (result.getSendStatus() != SendStatus.SEND_OK) {
                log.error("[MQ-Producer] Send failed: sessionId={}, requestId={}, status={}",
                        message.getSessionId(), message.getRequestId(), result.getSendStatus());
                throw new RuntimeException("消息发送失败: " + result.getSendStatus());
            }

            log.info("[MQ-Producer] Send success: sessionId={}, requestId={}, msgId={}, queue={}",
                    message.getSessionId(), message.getRequestId(),
                    result.getMsgId(), result.getMessageQueue().getQueueId());

        } catch (Exception e) {
            log.error("[MQ-Producer] Send exception: sessionId={}, requestId={}",
                    message.getSessionId(), message.getRequestId(), e);
            throw new RuntimeException("消息发送异常: " + e.getMessage(), e);
        }
    }
}

