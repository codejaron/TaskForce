package com.agent.domain.execution.model;

public enum AgentExecutionStatus {
    EXECUTING,
    IDLE,
    /**
     * Backward-compatibility value for historical persisted states.
     * New writes should use EXECUTING.
     */
    @Deprecated
    RUNNING,
    WAITING_REPLY,
    COMPLETED,
    FAILED
}
