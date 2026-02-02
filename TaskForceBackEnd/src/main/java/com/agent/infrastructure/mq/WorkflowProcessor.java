package com.agent.infrastructure.mq;

import com.agent.domain.orchestration.engine.AgentGraphRunner;
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

