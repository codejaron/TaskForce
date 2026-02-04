package com.agent.domain.context.listener;

import com.agent.domain.context.tool.ToolFileManager;
import com.agent.infrastructure.event.events.ToolCallCompleteEvent;
import com.agent.infrastructure.persistence.entity.ToolCall;
import com.agent.service.ToolCallService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用事件监听器
 * 监听工具调用完成事件，保存工具结果到文件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallEventListener {
    
    private final ToolFileManager toolFileManager;
    private final ToolCallService toolCallService;
    private final ObjectMapper objectMapper;
    
    @EventListener
    public void onToolCallComplete(ToolCallCompleteEvent event) {
        try {
            // 跳过 write_step_summary 工具（它本身就是写文件的）
            if ("write_step_summary".equals(event.getToolName())) {
                return;
            }
            
            // 从数据库查询完整的工具调用记录
            ToolCall toolCall = toolCallService.getByToolCallId(event.getToolCallId());
            if (toolCall == null) {
                log.warn("工具调用记录不存在: toolCallId={}", event.getToolCallId());
                return;
            }
            
            // 直接使用 event.getStepIndex()
            Integer stepIndex = event.getStepIndex();
            if (stepIndex == null || stepIndex <= 0) {
                log.warn("无法获取步骤索引: stepId={}, stepIndex={}", event.getStepId(), stepIndex);
                return;
            }
            
            // 解析工具参数
            Map<String, Object> args = parseToolArgs(toolCall.getToolArgs());
            
            // 保存工具结果到文件
            String filePath = toolFileManager.saveToolResult(
                    event.getSessionId(),
                    stepIndex,
                    event.getToolName(),
                    args,
                    event.getToolResult()
            );
            
            // 更新 tool_calls 表，记录文件路径
            toolCallService.updateFilePath(event.getToolCallId(), filePath);
            
            log.debug("工具结果已保存: toolCallId={}, filePath={}", 
                    event.getToolCallId(), filePath);
            
        } catch (Exception e) {
            log.error("保存工具结果失败: toolCallId={}", event.getToolCallId(), e);
        }
    }
    
    /**
     * 解析工具参数 JSON
     */
    private Map<String, Object> parseToolArgs(String toolArgsJson) {
        if (toolArgsJson == null || toolArgsJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(toolArgsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析工具参数失败: {}", toolArgsJson, e);
            return new HashMap<>();
        }
    }
}
