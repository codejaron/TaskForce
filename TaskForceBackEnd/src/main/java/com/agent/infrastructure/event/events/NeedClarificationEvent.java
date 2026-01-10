package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * 需要用户澄清事件
 */
@Getter
public class NeedClarificationEvent extends OrchestrationEvent {

    private final String question;
    private final String source;   // PLANNER / WORKER
    private final String stepId;   // Worker澄清时有值
    private final String agentId;  // Worker澄清时有值

    // 兼容旧构造函数（不带source）
    public NeedClarificationEvent(String sessionId, String question) {
        this(sessionId, question, null, null, null);
    }

    // 新构造函数（包含source信息）
    public NeedClarificationEvent(String sessionId, String question, String source, String stepId, String agentId) {
        super(sessionId);
        this.question = question;
        this.source = source;
        this.stepId = stepId;
        this.agentId = agentId;
    }

    @Override
    public String getEventType() {
        return "need_clarification";
    }
}
