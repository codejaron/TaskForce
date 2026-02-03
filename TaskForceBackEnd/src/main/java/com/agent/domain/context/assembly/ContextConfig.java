package com.agent.domain.context.assembly;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文组装配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "context.assembly")
public class ContextConfig {
    
    /**
     * 是否包含最近一步的完整输出
     */
    private boolean includeRecentOutput = true;
    
    /**
     * 最多展示多少历史步骤
     */
    private int maxHistorySteps = 20;
    
    /**
     * 是否启用 summary 兜底
     */
    private boolean summaryFallbackEnabled = true;
}
