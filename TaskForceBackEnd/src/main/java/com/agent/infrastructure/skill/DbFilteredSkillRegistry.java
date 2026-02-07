package com.agent.infrastructure.skill;

import com.agent.infrastructure.persistence.entity.Skill;
import com.agent.infrastructure.persistence.mapper.SkillMapper;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据库过滤的 Skill Registry
 * 委托 FileSystemSkillRegistry 加载文件系统 Skill，然后按数据库 enabled 字段过滤
 */
@Slf4j
public class DbFilteredSkillRegistry implements SkillRegistry {

    private final FileSystemSkillRegistry fileSystemRegistry;
    private final SkillMapper skillMapper;

    public DbFilteredSkillRegistry(FileSystemSkillRegistry fileSystemRegistry, SkillMapper skillMapper) {
        this.fileSystemRegistry = fileSystemRegistry;
        this.skillMapper = skillMapper;
    }

    /**
     * 列出所有启用的 Skill
     * 1. 从文件系统加载所有 Skill
     * 2. 查询数据库中的 enabled 状态
     * 3. 未录入数据库的 Skill 默认启用
     */
    @Override
    public List<SkillMetadata> listAll() {
        // 1. 从文件系统加载所有 Skill
        List<SkillMetadata> allSkills = fileSystemRegistry.listAll();

        if (allSkills.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查询数据库中所有 Skill 的启用状态
        List<Skill> dbSkills = skillMapper.selectList(null);
        Map<String, Boolean> enabledMap = dbSkills.stream()
                .collect(Collectors.toMap(Skill::getSkillId, Skill::getEnabled));

        // 3. 过滤：未录入数据库的默认启用，已录入的按 enabled 字段过滤
        List<SkillMetadata> enabledSkills = allSkills.stream()
                .filter(skill -> {
                    String skillId = skill.getName();
                    // 未录入数据库，默认启用
                    if (!enabledMap.containsKey(skillId)) {
                        log.debug("Skill {} not in database, enabled by default", skillId);
                        return true;
                    }
                    // 已录入数据库，按 enabled 字段过滤
                    boolean enabled = enabledMap.get(skillId);
                    log.debug("Skill {} enabled status from database: {}", skillId, enabled);
                    return enabled;
                })
                .collect(Collectors.toList());

        log.info("Loaded {} enabled skills out of {} total skills", enabledSkills.size(), allSkills.size());
        return enabledSkills;
    }

    @Override
    public Optional<SkillMetadata> get(String skillId) {
        // 先检查是否启用
        Optional<SkillMetadata> skillOpt = fileSystemRegistry.get(skillId);
        if (skillOpt.isEmpty()) {
            return Optional.empty();
        }

        // 检查数据库中的启用状态
        Skill dbSkill = skillMapper.selectOne(
                new LambdaQueryWrapper<Skill>().eq(Skill::getSkillId, skillId)
        );

        // 未录入数据库，默认启用
        if (dbSkill == null) {
            return skillOpt;
        }

        // 已录入数据库，检查 enabled 字段
        if (dbSkill.getEnabled()) {
            return skillOpt;
        } else {
            log.debug("Skill {} is disabled in database", skillId);
            return Optional.empty();
        }
    }

    @Override
    public boolean contains(String skillId) {
        return get(skillId).isPresent();
    }

    @Override
    public int size() {
        return listAll().size();
    }

    @Override
    public void reload() {
        log.info("Reloading skills from file system");
        fileSystemRegistry.reload();
    }

    @Override
    public String readSkillContent(String skillId) throws IOException {
        return fileSystemRegistry.readSkillContent(skillId);
    }

    @Override
    public String getSkillLoadInstructions() {
        return fileSystemRegistry.getSkillLoadInstructions();
    }

    @Override
    public String getRegistryType() {
        return "DbFilteredFileSystem";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return fileSystemRegistry.getSystemPromptTemplate();
    }
}
