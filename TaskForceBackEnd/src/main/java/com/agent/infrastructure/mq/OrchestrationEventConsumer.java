package com.agent.infrastructure.mq;

import com.agent.infrastructure.event.OrchestrationEvent;
import com.agent.infrastructure.event.RocketMQEventBus;
import com.agent.infrastructure.event.events.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${app.mq.topic:orchestration-events}",
        consumerGroup = "taskforce-sse-consumer",  // 消费者组，与生产者组不同
        messageModel = MessageModel.BROADCASTING
)
public class OrchestrationEventConsumer implements RocketMQListener<MessageExt> {

    private final RocketMQEventBus eventBus;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        //log.debug("[MQConsumer] Received message: {}", body);

        try {
            RocketMQEventBus.EventWrapper wrapper = objectMapper.readValue(body, RocketMQEventBus.EventWrapper.class);
            String sessionId = wrapper.sessionId();
            String eventType = wrapper.eventType();
            String eventData = wrapper.eventData();

            OrchestrationEvent event = deserializeEvent(eventType, eventData);
            if (event != null) {
                eventBus.pushToSubscriber(sessionId, event);
            }

        } catch (JsonProcessingException e) {
            log.error("[MQConsumer] Failed to parse message: {}", body, e);
        }
    }

    private OrchestrationEvent deserializeEvent(String eventType, String eventData) throws JsonProcessingException {
        return switch (eventType) {
            // 错误与澄清
            case "error" -> objectMapper.readValue(eventData, ErrorEvent.class);
            case "need_clarification" -> objectMapper.readValue(eventData, NeedClarificationEvent.class);

            // 计划相关
            case "plan_failed" -> objectMapper.readValue(eventData, PlanFailedEvent.class);
            case "plan_generated" -> objectMapper.readValue(eventData, PlanGeneratedEvent.class);
            case "planner_delta" -> objectMapper.readValue(eventData, PlannerDeltaEvent.class);
            case "planning_start" -> objectMapper.readValue(eventData, PlanningStartEvent.class);
            case "plan_updated" -> objectMapper.readValue(eventData, PlanUpdatedEvent.class);

            // 重规划
            case "replanner_delta" -> objectMapper.readValue(eventData, ReplannerDeltaEvent.class);
            case "replanning_start" -> objectMapper.readValue(eventData, ReplanningStartEvent.class);

            // 会话状态
            case "session_complete" -> objectMapper.readValue(eventData, SessionCompleteEvent.class);
            case "session_pause" -> objectMapper.readValue(eventData, SessionPauseEvent.class);
            case "session_resume" -> objectMapper.readValue(eventData, SessionResumeEvent.class);

            // 步骤执行
            case "step_blocked" -> objectMapper.readValue(eventData, StepBlockedEvent.class);
            case "step_completed" -> objectMapper.readValue(eventData, StepCompletedEvent.class);
            case "step_start" -> objectMapper.readValue(eventData, StepStartEvent.class);

            // 工具调用
            case "tool_call_complete" -> objectMapper.readValue(eventData, ToolCallCompleteEvent.class);
            case "tool_call_start" -> objectMapper.readValue(eventData, ToolCallStartEvent.class);

            // Worker
            case "worker_delta" -> objectMapper.readValue(eventData, WorkerDeltaEvent.class);

            default -> {
                log.warn("[MQConsumer] Unknown event type: {}", eventType);
                yield null;
            }
        };
    }
}
