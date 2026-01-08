package com.agent.config;

import com.agent.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 SSE 连接断开异常（Broken pipe）
     * 这是正常现象，客户端断开连接时服务端尝试写入导致
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.OK)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        // 这是 SSE 连接正常断开，不记录为错误
        if (e.getMessage().contains("Broken pipe")) {
            log.debug("SSE connection closed by client (broken pipe): {}", e.getMessage());
        } else {
            log.warn("Async request not usable: {}", e.getMessage());
        }
        // 不返回响应，因为连接已断开
    }

    /**
     * 处理 AsyncContext 竞态条件异常
     * 这是 SSE 连接断开时的正常现象
     */
    @ExceptionHandler(IllegalStateException.class)
    public void handleIllegalStateException(IllegalStateException e) {
        // 检查是否是 AsyncContext 相关错误
        if (e.getMessage() != null &&
            (e.getMessage().contains("AsyncContext") ||
             e.getMessage().contains("non-container thread"))) {
            // SSE 连接正常断开，不记录错误
            log.debug("SSE connection closed (AsyncContext race condition): {}", e.getMessage());
            // 不返回响应，因为连接已断开
        } else {
            // 其他 IllegalStateException 仍然记录为错误
            log.error("Illegal state exception", e);
        }
    }

    /**
     * 处理运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception", e);
        return ApiResponse.error(400, e.getMessage());
    }
    
    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.error("Validation exception: {}", errors);
        return ApiResponse.error(400, "参数校验失败");
    }
    
    /**
     * 处理所有其他异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return ApiResponse.error(500, "服务器内部错误");
    }
}
