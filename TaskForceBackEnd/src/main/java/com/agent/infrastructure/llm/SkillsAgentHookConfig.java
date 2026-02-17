package com.agent.infrastructure.llm;

import com.agent.infrastructure.skill.DbFilteredSkillRegistry;
import com.agent.infrastructure.skill.SkillConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@AutoConfigureAfter(SkillConfig.class)
public class SkillsAgentHookConfig {

    @Bean
    public SkillsAgentHook skillsAgentHook(
            DbFilteredSkillRegistry skillRegistry,
            @Autowired(required = false) @Qualifier("sandboxTools") List<ToolCallback> sandboxTools) {

        log.info("Creating SkillsAgentHook with {} skills", skillRegistry.size());

        // sandbox 工具绑定到每一个 skill 名上
        // 这样任何 skill 被 read_skill 后，sandbox 工具都会激活
        Map<String, List<ToolCallback>> groupedTools = new HashMap<>();
        if (sandboxTools != null && !sandboxTools.isEmpty()) {
            // 获取所有 skill 名称，为每个 skill 都绑定 sandbox 工具
            skillRegistry.listAll().forEach(skill -> {
                groupedTools.put(skill.getName(), new ArrayList<>(sandboxTools));
                log.info("Bound {} sandbox tools to skill '{}'", sandboxTools.size(), skill.getName());
            });
            log.info("Added sandbox tools to {} skills", groupedTools.size());
        } else {
            log.info("No sandbox tools available");
        }

        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(true)
                .groupedTools(groupedTools)
                .build();

        log.info("SkillsAgentHook created with {} skills and {} tool groups",
                hook.getSkillCount(), groupedTools.size());
        return hook;
    }
}
