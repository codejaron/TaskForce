package com.agent.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 工作流消息消费者
 *
 * 关键设计：
 * 1. consumeMode = ORDERLY：顺序消费，保证同一 Queue 的消息按序处理
 * 2. 同一 sessionId 的消息被路由到同一 Queue（生产者用 sessionId 做 hashKey）
 * 3. 处理失败抛异常，RocketMQ 自动重试（默认 16 次）
 * 4. 超时配置：consumeTimeout = 600000ms（10分钟），适配 LLM 长耗时调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = WorkflowProducer.TOPIC,
        consumerGroup = "taskforce-consumer",
        consumeMode = ConsumeMode.ORDERLY,
        consumeTimeout = 600000L  // 10 分钟，LLM 调用可能很慢
)
public class WorkflowConsumer implements RocketMQListener<String> {

    private final WorkflowProcessor workflowProcessor;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        TaskMessage taskMessage = null;
        try {
            taskMessage = objectMapper.readValue(message, TaskMessage.class);

            log.info("[MQ-Consumer] Received message: sessionId={}, requestId={}, type={}",
                    taskMessage.getSessionId(), taskMessage.getRequestId(), taskMessage.getType());

            // 调用处理器执行业务逻辑
            workflowProcessor.process(taskMessage);

            log.info("[MQ-Consumer] Message processed successfully: sessionId={}, requestId={}",
                    taskMessage.getSessionId(), taskMessage.getRequestId());

        } catch (Exception e) {
            String sessionId = taskMessage != null ? taskMessage.getSessionId() : "unknown";
            String requestId = taskMessage != null ? taskMessage.getRequestId() : "unknown";

            log.error("[MQ-Consumer] Message processing failed: sessionId={}, requestId={}, error={}",
                    sessionId, requestId, e.getMessage(), e);

            // 抛出异常，让 RocketMQ 重试
            throw new RuntimeException("消息处理失败: " + e.getMessage(), e);
        }
    }
}

