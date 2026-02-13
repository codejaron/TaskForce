package com.agent.infrastructure.agent;

public final class CheckpointThreadIds {

    private CheckpointThreadIds() {
    }

    public static String leadThreadId(String sessionId) {
        return sessionId + "_lead";
    }

    public static String workerThreadId(String instanceId) {
        return instanceId;
    }
}
