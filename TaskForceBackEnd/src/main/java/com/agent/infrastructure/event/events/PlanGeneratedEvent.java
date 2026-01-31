package com.agent.infrastructure.event.events;

import com.agent.domain.model.plan.ExecutionPlan;
import com.agent.domain.model.plan.PlanStep;
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
    // 新增：直接传 ExecutionPlan 的便捷构造函数
    public PlanGeneratedEvent(String sessionId, ExecutionPlan plan) {
        super(sessionId);
        this.planId = plan.getPlanId();
        this.goal = plan.getGoal();
        this.totalSteps = plan.getSteps().size();
        this.formattedPlan = formatPlan(plan);
    }

    private static String formatPlan(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标: ").append(plan.getGoal()).append("\n\n");
        for (PlanStep step : plan.getSteps()) {
            sb.append(String.format("%d. [%s] %s\n",
                    step.getStepIndex(),
                    step.getAssignedAgentName(),
                    step.getDescription()
            ));
        }
        return sb.toString();
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
