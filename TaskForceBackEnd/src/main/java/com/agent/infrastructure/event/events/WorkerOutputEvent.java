package com.agent.infrastructure.event.events;

import com.agent.infrastructure.event.OrchestrationEvent;
import lombok.Getter;

/**
 * Worker 输出事件
 * 用于实时推送 Worker 的执行输出
 */
@Getter
public class WorkerOutputEvent extends OrchestrationEvent {

    private String instanceId;
    private String taskId;
    private String output;

    // 无参构造函数（Jackson 反序列化需要）
    public WorkerOutputEvent() {
        super();
    }

    public WorkerOutputEvent(String sessionId, String instanceId, String taskId, String output) {
        super(sessionId);
        this.instanceId = instanceId;
        this.taskId = taskId;
        this.output = output;
    }

    @Override
    public String getEventType() {
        return "worker_output";
    }
}
