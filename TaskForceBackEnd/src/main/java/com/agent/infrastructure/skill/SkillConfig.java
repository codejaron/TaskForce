package com.agent.infrastructure.skill;

import com.agent.infrastructure.persistence.mapper.SkillMapper;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 配置类
 */
@Slf4j
@Configuration
public class SkillConfig {

    @Value("${skill.user-directory:#{null}}")
    private String userSkillsDirectory;

    @Value("${skill.project-directory:#{null}}")
    private String projectSkillsDirectory;

    @Value("${skill.auto-load:true}")
    private boolean autoLoad;

    /**
     * 创建 FileSystemSkillRegistry Bean
     */
    @Bean
    public FileSystemSkillRegistry fileSystemSkillRegistry() {
        FileSystemSkillRegistry.Builder builder = FileSystemSkillRegistry.builder()
                .autoLoad(autoLoad);

        if (userSkillsDirectory != null && !userSkillsDirectory.isEmpty()) {
            builder.userSkillsDirectory(userSkillsDirectory);
            log.info("Configured user skills directory: {}", userSkillsDirectory);
        }

        if (projectSkillsDirectory != null && !projectSkillsDirectory.isEmpty()) {
            builder.projectSkillsDirectory(projectSkillsDirectory);
            log.info("Configured project skills directory: {}", projectSkillsDirectory);
        }

        FileSystemSkillRegistry registry = builder.build();
        log.info("FileSystemSkillRegistry created with {} skills", registry.size());
        return registry;
    }

    /**
     * 创建 DbFilteredSkillRegistry Bean
     */
    @Bean
    public DbFilteredSkillRegistry dbFilteredSkillRegistry(
            FileSystemSkillRegistry fileSystemRegistry,
            SkillMapper skillMapper) {
        DbFilteredSkillRegistry registry = new DbFilteredSkillRegistry(fileSystemRegistry, skillMapper);
        log.info("DbFilteredSkillRegistry created with {} enabled skills", registry.size());
        return registry;
    }
}
