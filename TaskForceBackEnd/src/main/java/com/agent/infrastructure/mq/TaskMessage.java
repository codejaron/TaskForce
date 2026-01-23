package com.agent.infrastructure.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 工作流任务消息体
 * 用于 RocketMQ 消息传递
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID，用于顺序消费的 hash key
     */
    private String sessionId;

    /**
     * 请求 ID，用于幂等检查
     */
    private String requestId;

    /**
     * 消息类型：SUBMIT（新任务）/ RESUME（恢复执行）
     */
    private MessageType type;

    /**
     * 用户输入内容
     */
    private String userInput;

    /**
     * 消息创建时间戳
     */
    private Instant timestamp;

    /**
     * 重试次数（用于日志追踪）
     */
    private int retryCount;

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        /**
         * 提交新任务
         */
        SUBMIT,

        /**
         * 恢复执行（用户回答问题后）
         */
        RESUME
    }

    /**
     * 创建提交任务消息
     */
    public static TaskMessage ofSubmit(String sessionId, String requestId, String userInput) {
        return TaskMessage.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .type(MessageType.SUBMIT)
                .userInput(userInput)
                .timestamp(Instant.now())
                .retryCount(0)
                .build();
    }

    /**
     * 创建恢复执行消息
     */
    public static TaskMessage ofResume(String sessionId, String requestId, String userAnswer) {
        return TaskMessage.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .type(MessageType.RESUME)
                .userInput(userAnswer)
                .timestamp(Instant.now())
                .retryCount(0)
                .build();
    }
}

