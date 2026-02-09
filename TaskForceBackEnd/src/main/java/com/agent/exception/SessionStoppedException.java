package com.agent.exception;

/**
 * 会话停止异常
 * 当用户主动停止会话时抛出此异常，用于统一标识停止操作
 */
public class SessionStoppedException extends RuntimeException {

    private final String sessionId;

    public SessionStoppedException(String sessionId, String message) {
        super(message);
        this.sessionId = sessionId;
    }

    public SessionStoppedException(String sessionId) {
        this(sessionId, "Session has been stopped by user: " + sessionId);
    }

    public String getSessionId() {
        return sessionId;
    }
}
