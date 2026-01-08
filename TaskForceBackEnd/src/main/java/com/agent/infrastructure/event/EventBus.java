package com.agent.infrastructure.event;

import reactor.core.publisher.Flux;

/**
 * 事件总线接口
 * 支持 Session 级别的 Pub/Sub
 */
public interface EventBus {

    /**
     * 发布事件到指定 Session 的频道
     *
     * @param sessionId 会话 ID
     * @param event     事件
     */
    void publish(String sessionId, OrchestrationEvent event);

    /**
     * 订阅指定 Session 的事件流
     *
     * @param sessionId 会话 ID
     * @return 事件流
     */
    Flux<OrchestrationEvent> subscribe(String sessionId);

    /**
     * 取消订阅并清理指定 Session 的频道
     *
     * @param sessionId 会话 ID
     */
    void unsubscribe(String sessionId);

    /**
     * 检查指定 Session 是否有活跃的订阅者
     *
     * @param sessionId 会话 ID
     * @return 是否有订阅者
     */
    boolean hasSubscribers(String sessionId);
}
