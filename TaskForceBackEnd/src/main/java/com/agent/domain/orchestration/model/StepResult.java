package com.agent.domain.orchestration.model;

import lombok.Getter;

/**
 * 步骤执行结果
 */
@Getter
public class StepResult {

    private final ResultType type;
    private final String output;
    private final String blockedReason;
    private final String question;

    private StepResult(ResultType type, String output, String blockedReason, String question) {
        this.type = type;
        this.output = output;
        this.blockedReason = blockedReason;
        this.question = question;
    }

    /**
     * 结果类型
     */
    public enum ResultType {
        SUCCESS,
        BLOCKED,
        NEEDS_USER_INPUT
    }

    // === 静态工厂方法 ===

    /**
     * 成功完成
     */
    public static StepResult success(String output) {
        return new StepResult(ResultType.SUCCESS, output, null, null);
    }

    /**
     * 阻塞
     */
    public static StepResult blocked(String reason) {
        return new StepResult(ResultType.BLOCKED, null, reason, null);
    }

    /**
     * 需要用户输入
     */
    public static StepResult needsUserInput(String question) {
        return new StepResult(ResultType.NEEDS_USER_INPUT, null, null, question);
    }

    // === 判断方法 ===

    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return type == ResultType.SUCCESS;
    }

    /**
     * 是否阻塞
     */
    public boolean isBlocked() {
        return type == ResultType.BLOCKED;
    }

    /**
     * 是否需要用户输入
     */
    public boolean needsUserInput() {
        return type == ResultType.NEEDS_USER_INPUT;
    }
}
