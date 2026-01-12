package com.agent.infrastructure.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Session 上下文持有者
 * 使用 ThreadLocal 在当前线程中存储 sessionId，实现隐式上下文传递
 */
@Slf4j
@Component
public class SessionContextHolder {

    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前线程的 sessionId
     */
    public static void setSessionId(String sessionId) {
        SESSION_ID_HOLDER.set(sessionId);
        log.debug("[SessionContextHolder] Set sessionId: {}", sessionId);
    }

    /**
     * 获取当前线程的 sessionId
     * @throws IllegalStateException 如果 sessionId 未设置
     */
    public static String getSessionId() {
        String sessionId = SESSION_ID_HOLDER.get();
        if (sessionId == null) {
            throw new IllegalStateException("SessionId not set in current thread. Please ensure StepExecutor has set the context.");
        }
        return sessionId;
    }

    /**
     * 清理当前线程的 sessionId
     * 必须在请求结束时调用，避免线程池复用导致的内存泄漏
     */
    public static void clear() {
        String sessionId = SESSION_ID_HOLDER.get();
        SESSION_ID_HOLDER.remove();
        log.debug("[SessionContextHolder] Cleared sessionId: {}", sessionId);
    }

    /**
     * 检查当前线程是否已设置 sessionId
     */
    public static boolean isSet() {
        return SESSION_ID_HOLDER.get() != null;
    }
}
