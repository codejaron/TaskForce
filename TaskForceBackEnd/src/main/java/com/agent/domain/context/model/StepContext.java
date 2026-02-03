package com.agent.domain.context.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单步上下文
 * 包含步骤的索引信息和摘要内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepContext {
    
    /**
     * 步骤索引（从 1 开始）
     */
    private int stepIndex;
    
    /**
     * 步骤标题
     */
    private String stepTitle;
    
    /**
     * summary.md 路径
     */
    private String summaryPath;
    
    /**
     * output.md 路径
     */
    private String outputPath;
    
    /**
     * 工具结果文件列表
     */
    @Builder.Default
    private List<String> toolFiles = new ArrayList<>();
    
    // ===== 从 summary.md 解析的内容 =====
    
    /**
     * 结论
     */
    private String conclusion;
    
    /**
     * 关键发现
     */
    @Builder.Default
    private List<String> findings = new ArrayList<>();
    
    /**
     * 下一步建议
     */
    private String nextSuggestion;
    
    /**
     * 是否有摘要文件
     */
    public boolean hasSummary() {
        return conclusion != null && !conclusion.isEmpty();
    }
}
