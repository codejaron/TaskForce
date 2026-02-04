package com.agent.mcpserver.context;

/**
 * Session 上下文
 * 使用 ThreadLocal 存储当前线程的 sessionId 和 stepIndex
 */
public class SessionContext {
    
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> STEP_INDEX = new ThreadLocal<>();
    
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
     * 设置当前线程的 stepIndex
     */
    public static void setStepIndex(Integer stepIndex) {
        STEP_INDEX.set(stepIndex);
    }
    
    /**
     * 获取当前线程的 stepIndex
     */
    public static Integer getStepIndex() {
        return STEP_INDEX.get();
    }
    
    /**
     * 清除当前线程的上下文
     */
    public static void clear() {
        SESSION_ID.remove();
        STEP_INDEX.remove();
    }
}
