package com.agent.api.response;

/**
 * 提交响应
 * Fire-and-Forget 模式下的响应
 */
public record SubmitResponse(
        String requestId,
        String status  // PROCESSING, PAUSED, RESUMED, ERROR
) {
    public static SubmitResponse processing(String requestId) {
        return new SubmitResponse(requestId, "PROCESSING");
    }

    public static SubmitResponse resumed(String requestId) {
        return new SubmitResponse(requestId, "RESUMED");
    }

    public static SubmitResponse paused(String requestId) {
        return new SubmitResponse(requestId, "PAUSED");
    }

    public static SubmitResponse error(String requestId, String message) {
        return new SubmitResponse(requestId, "ERROR: " + message);
    }
}
