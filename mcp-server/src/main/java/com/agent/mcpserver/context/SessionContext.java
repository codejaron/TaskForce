package com.agent.mcpserver.context;

/**
 * Session 上下文
 * 使用 ThreadLocal 存储当前线程的 sessionId
 */
public class SessionContext {
    
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    
    /**
     * 设置当前线程的 sessionId
     */
    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }
    
    /**
     * 获取当前线程的 sessionId
     */
    public static String getSessionId() {
        return SESSION_ID.get();
    }
    
    /**
     * 清除当前线程的 sessionId
     */
    public static void clear() {
        SESSION_ID.remove();
    }
}
