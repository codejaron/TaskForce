package com.agent.domain.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用记录 DTO
 * 用于跟踪Worker执行的工具调用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具参数（JSON格式）
     */
    private String toolArgs;

    /**
     * 工具执行结果
     */
    private String toolResult;

    /**
     * 执行耗时（毫秒）
     */
    private Long executionTime;

    /**
     * 执行状态：SUCCESS / FAILED
     */
    private String status;

    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
}
