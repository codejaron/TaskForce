package com.agent.infrastructure.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 编排事件基类
 * 所有编排相关的事件都继承此类
 */
@Getter
public abstract class OrchestrationEvent {

    private static final ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private final String eventId;
    private final String sessionId;
    private final LocalDateTime timestamp;

    // 无参构造函数（Jackson 反序列化需要）
    protected OrchestrationEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.sessionId = null;
        this.timestamp = LocalDateTime.now();
    }

    protected OrchestrationEvent(String sessionId) {
        this.eventId = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 获取事件类型（用于 SSE event 字段）
     */
    @JsonIgnore
    public abstract String getEventType();

    /**
     * 转换为 JSON 字符串（用于 SSE data 字段）
     */
    public String toJson() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize event\"}";
        }
    }
}
