package com.agent.infrastructure.mq;

import com.agent.domain.orchestration.graph.AgentGraphRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工作流处理器（重构版本）
 * 使用 AgentGraphRunner 执行工作流
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowProcessor {

    private final AgentGraphRunner graphRunner;
    private final com.agent.service.SessionStopService sessionStopService;
    private final com.agent.service.SessionService sessionService;

    
    /**
     * 处理任务消息
     * 
     * @param message 任务消息
     */
    public void process(TaskMessage message) {
        String sessionId = message.getSessionId();
        String requestId = message.getRequestId();
        String content = message.getUserInput();
        
        log.info("[Processor] Processing message: sessionId={}, requestId={}, type={}",
                sessionId, requestId, message.getType());
        
        // 检查会话是否已被停止
        if (sessionStopService.shouldStop(sessionId)) {
            log.info("[Processor] Session is stopped, skipping execution: sessionId={}", sessionId);
            return;
        }
        
        // 检查会话状态
        try {
            var session = sessionService.getSessionById(sessionId);
            if ("PAUSED".equals(session.getStatus())) {
                log.info("[Processor] Session is paused, skipping execution: sessionId={}", sessionId);
                return;
            }
        } catch (Exception e) {
            log.warn("[Processor] Failed to check session status: sessionId={}", sessionId, e);
        }
        
        switch (message.getType()) {
            case SUBMIT -> {
                // 提交新任务（Graph 内部会通过 EventBus 发事件）
                graphRunner.submit(sessionId, requestId, content)
                    .subscribe();  // 异步执行
            }
            case RESUME -> {
                // 恢复执行
                graphRunner.resume(sessionId, content)
                    .subscribe();  // 异步执行
            }
        }
    }
}

