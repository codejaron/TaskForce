package com.agent.domain.plan;

import com.agent.domain.plan.PlanStep;
import com.agent.infrastructure.persistence.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务执行上下文
 * 封装 Worker 执行所需的全部上下文信息
 *
 * 包含：
 * - 用户目标 (来自 ExecutionPlan.goal)
 * - 最近对话历史 (来自 Message 表)
 * - 共享数据/黑板 (来自 session_artifact 表)
 * - 当前步骤信息 (来自 ExecutionPlan)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskContext {

    /**
     * 用户目标（来自 ExecutionPlan.goal）
     */
    private String userGoal;

    /**
     * 最近的对话历史（来自 Message 表，最近 10 条）
     */
    @Builder.Default
    private List<Message> recentHistory = List.of();

    /**
     * 共享数据/黑板（来自 session_artifact 表）
     * Key: artifact_key, Value: artifact_value
     */
    @Builder.Default
    private Map<String, String> sharedData = new HashMap<>();

    /**
     * 当前步骤信息
     */
    private PlanStep currentStep;

    /**
     * 会话 ID（用于调试）
     */
    private String sessionId;

    // === 辅助方法 ===

    /**
     * 获取共享数据的数量
     */
    public int getSharedDataCount() {
        return sharedData != null ? sharedData.size() : 0;
    }

    /**
     * 获取历史消息数量
     */
    public int getHistoryCount() {
        return recentHistory != null ? recentHistory.size() : 0;
    }

    /**
     * 检查是否有共享数据
     */
    public boolean hasSharedData() {
        return sharedData != null && !sharedData.isEmpty();
    }

    /**
     * 检查是否有历史记录
     */
    public boolean hasHistory() {
        return recentHistory != null && !recentHistory.isEmpty();
    }

    /**
     * 获取指定 key 的 artifact（带默认值）
     */
    public String getArtifact(String key, String defaultValue) {
        return sharedData != null ? sharedData.getOrDefault(key, defaultValue) : defaultValue;
    }
}
