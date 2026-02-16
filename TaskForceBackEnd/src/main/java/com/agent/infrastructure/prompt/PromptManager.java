package com.agent.infrastructure.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Prompt管理器
 * 集中管理所有Prompt模板和组装逻辑
 */
@Slf4j
@Component
public class PromptManager {

    /**
     * 构建 Team Lead Prompt
     *
     * @param userGoal 用户目标
     * @return Team Lead Prompt
     */
    public String buildTeamLeadPrompt(String userGoal) {
        return """
        你是团队负责人，负责协调多个 Worker 完成用户目标。

        ## 工作流程
        1. 分析目标 → 用 create_task 创建任务，正确设置 blockedBy 依赖
        2. 用 spawn_worker 为可并行的任务创建 Worker，数量匹配并行度
        3. 用 list_tasks 监控进度并判断是否还有可分配任务
        4. 用 send_message 指导特定 Worker，broadcast 仅用于紧急情况
           给已有 Worker 派任务时，send_message 必须传 assignTask=true 和 taskId
        5. 用 reply_user 向用户汇报关键进展（不要每步都报）
        6. 完成后用 shutdown_worker 清理，失败任务最多重试 2 次

        ## 任务设计
        - 每个任务 = 一个明确可验证的子目标（不要太细也不要太粗）
        - blockedBy 表达数据依赖，无依赖的任务设为 []
        - 禁止循环依赖

        ## Worker 数量
        - Worker 数 ≤ 可并行任务数（最多 5 个）
        - 全串行或有空闲 Worker 时不要创建新的

        ## 失败处理
        - 任务失败：分析原因 → 重试一次 → 仍失败则向用户汇报
        - 多个任务系统性失败时立即停止并汇报

        ## 用户目标
        """ + userGoal + "\n\n立即开始，自主工作。\n";
    }

    /**
     * 构建 Worker Instance Prompt
     *
     * @param workerName Worker 名称
     * @param workerRole Worker 角色描述
     * @param taskDescription 任务描述
     * @param expectedOutput 期望输出
     * @param contextInfo 上下文信息（可选）
     * @return Worker Instance Prompt
     */
    public String buildWorkerInstancePrompt(
            String workerName,
            String workerRole,
            String taskDescription,
            String expectedOutput,
            String contextInfo) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个任务执行专家：").append(workerName).append("\n\n");

        prompt.append("【你的角色】\n");
        prompt.append(workerRole != null ? workerRole : "执行分配的任务").append("\n\n");

        if (contextInfo != null && !contextInfo.isEmpty()) {
            prompt.append("【上下文信息】\n");
            prompt.append(contextInfo).append("\n\n");
        }

        prompt.append("【当前任务】\n");
        prompt.append(taskDescription).append("\n\n");

        if (expectedOutput != null && !expectedOutput.isEmpty()) {
            prompt.append("【期望输出】\n");
            prompt.append(expectedOutput).append("\n\n");
        }

        prompt.append("""
            【执行规则】
            1. 严格按照任务指令执行
            2. 使用可用的工具完成任务
            3. 遇到无法解决的技术问题时，输出 "BLOCKED: 原因"
            4. 需要用户提供更多信息时，输出 "NEED_USER_INPUT: 问题"
            5. 完成后直接输出结果

            【工作空间】
            你有一个独立的工作区用于保存产出。

            现在开始执行任务。
            """);

        log.debug("[PromptManager] Built Worker Instance Prompt: worker={}, task={}",
                workerName, taskDescription);

        return prompt.toString();
    }

    /**
     * 组合系统 Prompt 和用户消息
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息（可选）
     * @return 组合后的完整 Prompt
     */
    public String combinePrompts(String systemPrompt, String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n" + userMessage;
    }
}
