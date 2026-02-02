package com.agent.domain.tool;

import com.agent.domain.tool.ToolInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent工具详情DTO
 * 包含工具元数据和启用状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolDetail {

    private Long id; // 关联记录ID
    private Long agentId;
    private String toolId;
    private Boolean enabled;
    private ToolInfo toolInfo; // 工具元数据（可能为null，如果工具已被删除）
    private LocalDateTime addedAt;
}
