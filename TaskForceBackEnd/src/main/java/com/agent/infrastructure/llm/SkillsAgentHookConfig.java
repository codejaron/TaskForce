package com.agent.infrastructure.llm;

import com.agent.infrastructure.skill.DbFilteredSkillRegistry;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillsAgentHook 配置类
 * 用于创建配置了 Skill 和 Sandbox 的 SkillsAgentHook
 */
@Slf4j
@Configuration
public class SkillsAgentHookConfig {

    /**
     * 创建 SkillsAgentHook
     * 仅在 DbFilteredSkillRegistry 存在时创建
     * Sandbox 工具是可选的，如果存在则添加到 groupedTools 中
     */
    @Bean
    @ConditionalOnBean(DbFilteredSkillRegistry.class)
    public SkillsAgentHook skillsAgentHook(
            DbFilteredSkillRegistry skillRegistry,
            @Autowired(required = false) List<ToolCallback> sandboxTools) {

        log.info("Creating SkillsAgentHook with {} skills", skillRegistry.size());

        // 创建 groupedTools（将 Sandbox 工具分组）
        Map<String, List<ToolCallback>> groupedTools = new HashMap<>();
        if (sandboxTools != null && !sandboxTools.isEmpty()) {
            groupedTools.put("sandbox", new ArrayList<>(sandboxTools));
            log.info("Added {} sandbox tools to SkillsAgentHook", sandboxTools.size());
        } else {
            log.info("No sandbox tools available (sandbox.enabled=false or not configured)");
        }

        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(true)  // 支持自动重载
                .groupedTools(groupedTools)  // 添加分组工具
                .build();

        log.info("SkillsAgentHook created successfully with {} skills and {} tool groups",
                hook.getSkillCount(), groupedTools.size());
        return hook;
    }
}
