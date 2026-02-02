package com.agent.domain.session;

import com.agent.domain.agent.AgentProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话状态
 * 管理 A2A 群组会话的状态信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionState {

    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 会话名称
     */
    private String name;

    /**
     * 参与会话的智能体列表
     */
    @Builder.Default
    private List<AgentProfile> agents = new ArrayList<>();

    /**
     * 编排模式
     */
    @Builder.Default
    private OrchestrationMode orchestrationMode = OrchestrationMode.ROUND_ROBIN;

    /**
     * 当前轮次
     */
    @Builder.Default
    private Integer currentTurn = 0;

    /**
     * 最大轮次（防死锁熔断）
     */
    @Builder.Default
    private Integer maxTurns = 20;

    /**
     * 当前发言者的索引
     */
    @Builder.Default
    private Integer currentSpeakerIndex = 0;

    /**
     * 会话状态
     */
    @Builder.Default
    private Status status = Status.CREATED;

    /**
     * 创建时间
     */
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * 最后活跃时间
     */
    @Builder.Default
    private Instant lastActiveAt = Instant.now();

    /**
     * 编排模式枚举
     */
    public enum OrchestrationMode {
        /**
         * 自动轮询：A -> B -> C -> A...
         */
        ROUND_ROBIN,
        /**
         * 随机选择：随机选择下一个发言者
         */
        RANDOM,
        /**
         * 自动编排：由 LLM 决定谁接话 (AutoGen 核心功能)
         */
        AUTO,
        /**
         * 手动指定：用户点击头像指定下一位发言者
         */
        MANUAL
    }

    /**
     * 会话状态枚举
     */
    public enum Status {
        PENDING,    // 待处理 / 初始状态（历史数据兼容）
        CREATED,    // 已创建
        RUNNING,    // 运行中
        PAUSED,     // 已暂停
        WAITING_USER, // 等待用户输入（暂停但不同于人工暂停）
        COMPLETED,  // 已完成
        ERROR       // 出错
    }

    /**
     * 获取下一个发言者（轮询模式）
     */
    public AgentProfile getNextSpeaker() {
        if (agents.isEmpty()) {
            return null;
        }
        currentSpeakerIndex = (currentSpeakerIndex + 1) % agents.size();
        return agents.get(currentSpeakerIndex);
    }

    /**
     * 获取当前发言者
     */
    public AgentProfile getCurrentSpeaker() {
        if (agents.isEmpty() || currentSpeakerIndex >= agents.size()) {
            return null;
        }
        return agents.get(currentSpeakerIndex);
    }

    /**
     * 检查是否达到熔断条件
     */
    public boolean shouldCircuitBreak() {
        return currentTurn >= maxTurns;
    }

    /**
     * 递增轮次
     */
    public void incrementTurn() {
        currentTurn++;
        lastActiveAt = Instant.now();
    }
}
