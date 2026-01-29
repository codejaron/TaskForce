package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;

/**
 * 计划生成完成事件
 */
@Getter
public class PlanGeneratedEvent extends OrchestrationEvent {

    private String planId;
    private String goal;
    private int totalSteps;
    private String formattedPlan;  // 完整的格式化计划内容

    // 无参构造函数（Jackson 反序列化需要）
    public PlanGeneratedEvent() {
        super();
    }

    public PlanGeneratedEvent(String sessionId, String planId, String goal, int totalSteps, String formattedPlan) {
        super(sessionId);
        this.planId = planId;
        this.goal = goal;
        this.totalSteps = totalSteps;
        this.formattedPlan = formattedPlan;
    }

    // 兼容前端的字段名
    public int getStepCount() {
        return totalSteps;
    }

    @Override
    public String getEventType() {
        return "plan_generated";
    }
}
