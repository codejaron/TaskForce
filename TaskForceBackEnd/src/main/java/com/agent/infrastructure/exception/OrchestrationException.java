package com.agent.infrastructure.exception;

/**
 * 编排异常
 */
public class OrchestrationException extends RuntimeException {

    public OrchestrationException(String message) {
        super(message);
    }

    public OrchestrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
