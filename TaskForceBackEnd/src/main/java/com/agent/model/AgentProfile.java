package com.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体配置实体
 * 定义智能体的身份、模型参数和MCP工具权限
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentProfile {

    /**
     * 智能体唯一标识
     */
    private String id;

    /**
     * 智能体名称（如：程序员、产品经理、审计员）
     */
    private String name;

    /**
     * 头像URL或Base64编码
     */
    private String avatar;

    /**
     * 角色描述 / System Prompt
     * 定义智能体的人格和行为准则
     */
    private String systemPrompt;

    /**
     * 使用的模型名称（如：gpt-4o, claude-3.5-sonnet, deepseek-chat）
     */
    @Builder.Default
    private String modelName = "deepseek-chat";

    /**
     * 温度参数 (0.0 - 1.0)
     * 高创造力智能体设为 0.9，严谨执行智能体设为 0.2
     */
    @Builder.Default
    private Double temperature = 0.7;

    /**
     * 已选择的 MCP 工具 ID 列表
     * 实现能力隔离：程序员只能用 FileSystem/Git，产品经理只能用 WebSearch
     */
    @Builder.Default
    private List<String> selectedMcpTools = new ArrayList<>();

    /**
     * 智能体描述（简短说明）
     */
    private String description;

    /**
     * 终止关键词（用于 AutoGen 协议）
     * 当智能体输出此关键词时，表示任务已完成
     */
    @Builder.Default
    private String terminationKeyword = "[讨论结束]";

    /**
     * 是否启用
     */
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 最大token数
     */
    @Builder.Default
    private Integer maxTokens = 4096;

    /**
     * 角色类型：MODERATOR | WORKER | PLANNER
     */
    public enum RoleType {
        MODERATOR,
        WORKER,
        PLANNER
    }

    private RoleType roleType;
}
