package com.agent.application.orchestration;

import com.agent.domain.model.plan.*;
import com.agent.infrastructure.mq.TaskMessage;
import com.agent.infrastructure.mq.WorkflowProcessor;
import com.agent.infrastructure.mq.WorkflowProducer;
import com.agent.service.SessionStopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 工作流引擎
 * 核心编排组件，负责接收请求并发送到 MQ
 *
 * 1. Fire-and-Forget：HTTP 线程立即返回
 * 2. MQ 解耦：发送消息到 RocketMQ，由 Consumer 异步处理
 * 3. 顺序保证：同一 sessionId 的消息发到同一 Queue
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final StateManager stateManager;
    private final SessionStopService sessionStopService;
    private final WorkflowProducer workflowProducer;
    private  final WorkflowProcessor workflowProcessor;

    /**
     * 提交用户输入 - Fire and Forget
     * HTTP 线程立即返回 requestId
     *
     * @throws RuntimeException 消息发送失败时抛出
     */
    public String submitUserInput(String sessionId, String userText) {
        String requestId = UUID.randomUUID().toString();
        log.info("[WorkflowEngine] Submitting user input: sessionId={}, requestId={}", sessionId, requestId);

        // 清除停止标志
        sessionStopService.clearStop(sessionId);

        // 记录用户输入
        stateManager.recordUserInput(sessionId, requestId, userText);

        // 构造消息并发送到 MQ
        TaskMessage message = TaskMessage.ofSubmit(sessionId, requestId, userText);
        workflowProducer.send(message);  // 发送失败会抛异常
        // 直接调用处理器
        //workflowProcessor.process(message);

        log.info("[WorkflowEngine] Message sent to MQ: sessionId={}, requestId={}", sessionId, requestId);
        return requestId;
    }

    /**
     * 恢复执行（用户回答问题后）
     *
     * @throws RuntimeException 消息发送失败时抛出
     */
    public String resume(String sessionId, String userAnswer) {
        String requestId = UUID.randomUUID().toString();
        log.info("[WorkflowEngine] Resuming session: sessionId={}, requestId={}", sessionId, requestId);

        sessionStopService.clearStop(sessionId);
        stateManager.recordUserInput(sessionId, requestId, userAnswer);

        ExecutionPlan plan = stateManager.loadPlan(sessionId);
        if (plan != null && plan.getStatus() == PlanStatus.PAUSED) {
            plan.resume();
            stateManager.savePlan(plan);
        }

        // 构造消息并发送到 MQ
        TaskMessage message = TaskMessage.ofResume(sessionId, requestId, userAnswer);
        workflowProducer.send(message);  // 发送失败会抛异常

        log.info("[WorkflowEngine] Resume message sent to MQ: sessionId={}, requestId={}", sessionId, requestId);
        return requestId;
    }

    /**
     * 获取当前状态
     */
    public ExecutionPlan getState(String sessionId) {
        return stateManager.loadPlan(sessionId);
    }
}
