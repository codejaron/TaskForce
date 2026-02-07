package com.agent.service;

import com.agent.api.dto.SkillDTO;
import com.agent.infrastructure.persistence.entity.Skill;
import com.agent.infrastructure.persistence.mapper.SkillMapper;
import com.agent.infrastructure.skill.DbFilteredSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Skill 服务
 */
@Slf4j
@Service
public class SkillService {

    private final SkillMapper skillMapper;
    private final DbFilteredSkillRegistry skillRegistry;

    public SkillService(SkillMapper skillMapper, DbFilteredSkillRegistry skillRegistry) {
        this.skillMapper = skillMapper;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 列出所有 Skill
     */
    public List<SkillDTO> listAll() {
        // 从文件系统加载所有 Skill
        List<SkillMetadata> allSkills = skillRegistry.listAll();

        // 查询数据库中的 Skill 状态
        List<Skill> dbSkills = skillMapper.selectList(null);

        return allSkills.stream()
                .map(metadata -> {
                    SkillDTO dto = new SkillDTO();
                    dto.setSkillId(metadata.getName());
                    dto.setName(metadata.getName());
                    dto.setPath(metadata.getSkillPath());

                    // 查找数据库中的记录
                    Optional<Skill> dbSkill = dbSkills.stream()
                            .filter(s -> s.getSkillId().equals(metadata.getName()))
                            .findFirst();

                    if (dbSkill.isPresent()) {
                        dto.setId(dbSkill.get().getId());
                        dto.setEnabled(dbSkill.get().getEnabled());
                        dto.setCreatedAt(dbSkill.get().getCreatedAt());
                        dto.setUpdatedAt(dbSkill.get().getUpdatedAt());
                    } else {
                        // 未录入数据库，默认启用
                        dto.setEnabled(true);
                    }

                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取 Skill 详情
     */
    public SkillDTO getById(String skillId) {
        // 从文件系统获取 Skill 元数据
        Optional<SkillMetadata> metadataOpt = skillRegistry.get(skillId);
        if (metadataOpt.isEmpty()) {
            throw new RuntimeException("Skill not found: " + skillId);
        }

        SkillMetadata metadata = metadataOpt.get();
        SkillDTO dto = new SkillDTO();
        dto.setSkillId(metadata.getName());
        dto.setName(metadata.getName());
        dto.setPath(metadata.getSkillPath());

        // 查询数据库中的记录
        Skill dbSkill = skillMapper.selectOne(
                new LambdaQueryWrapper<Skill>().eq(Skill::getSkillId, skillId)
        );

        if (dbSkill != null) {
            dto.setId(dbSkill.getId());
            dto.setEnabled(dbSkill.getEnabled());
            dto.setCreatedAt(dbSkill.getCreatedAt());
            dto.setUpdatedAt(dbSkill.getUpdatedAt());
        } else {
            dto.setEnabled(true);
        }

        return dto;
    }

    /**
     * 启用 Skill
     */
    @Transactional
    public void enable(String skillId) {
        updateSkillStatus(skillId, true);
        log.info("Enabled skill: {}", skillId);
    }

    /**
     * 禁用 Skill
     */
    @Transactional
    public void disable(String skillId) {
        updateSkillStatus(skillId, false);
        log.info("Disabled skill: {}", skillId);
    }

    /**
     * 更新 Skill 状态
     */
    private void updateSkillStatus(String skillId, boolean enabled) {
        // 检查 Skill 是否存在
        Optional<SkillMetadata> metadataOpt = skillRegistry.get(skillId);
        if (metadataOpt.isEmpty()) {
            throw new RuntimeException("Skill not found: " + skillId);
        }

        SkillMetadata metadata = metadataOpt.get();

        // 查询数据库中的记录
        Skill dbSkill = skillMapper.selectOne(
                new LambdaQueryWrapper<Skill>().eq(Skill::getSkillId, skillId)
        );

        if (dbSkill != null) {
            // 更新现有记录
            dbSkill.setEnabled(enabled);
            dbSkill.setUpdatedAt(LocalDateTime.now());
            skillMapper.updateById(dbSkill);
        } else {
            // 创建新记录
            Skill newSkill = new Skill();
            newSkill.setSkillId(skillId);
            newSkill.setName(metadata.getName());
            newSkill.setPath(metadata.getSkillPath());
            newSkill.setEnabled(enabled);
            newSkill.setCreatedAt(LocalDateTime.now());
            newSkill.setUpdatedAt(LocalDateTime.now());
            skillMapper.insert(newSkill);
        }

        // 重新加载 Skill Registry
        skillRegistry.reload();
    }

    /**
     * 从 Git 导入 Skill
     */
    @Transactional
    public void importFromGit(String gitUrl, String branch, String targetDirectory) {
        log.info("Importing skill from Git: url={}, branch={}, target={}", gitUrl, branch, targetDirectory);

        if (branch == null || branch.isEmpty()) {
            branch = "main";
        }

        try {
            // 1. 克隆仓库到临时目录
            String tempDir = System.getProperty("java.io.tmpdir") + "/skill-import-" + System.currentTimeMillis();
            cloneRepository(gitUrl, branch, tempDir);

            // 2. 验证 Skill 结构
            validateSkillStructure(tempDir);

            // 3. 提取 Skill 名称
            String skillName = extractSkillName(tempDir);

            // 4. 移动到目标目录
            String finalPath = moveToTargetDirectory(tempDir, targetDirectory, skillName);

            // 5. 录入数据库
            registerSkillInDatabase(skillName, finalPath);

            // 6. 重新加载 Skill Registry
            skillRegistry.reload();

            log.info("Successfully imported skill: {} from {}", skillName, gitUrl);

        } catch (Exception e) {
            log.error("Failed to import skill from Git: {}", gitUrl, e);
            throw new RuntimeException("Failed to import skill: " + e.getMessage(), e);
        }
    }

    /**
     * 克隆 Git 仓库
     */
    private void cloneRepository(String gitUrl, String branch, String targetPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--branch", branch, "--depth", "1", gitUrl, targetPath
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Git clone failed with exit code: " + exitCode);
            }

            log.info("Successfully cloned repository to: {}", targetPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone repository: " + e.getMessage(), e);
        }
    }

    /**
     * 验证 Skill 结构
     */
    private void validateSkillStructure(String skillPath) {
        java.io.File skillDir = new java.io.File(skillPath);
        if (!skillDir.exists() || !skillDir.isDirectory()) {
            throw new RuntimeException("Skill directory does not exist: " + skillPath);
        }

        // 检查必需的文件和目录
        java.io.File skillMd = new java.io.File(skillDir, "SKILL.md");
        if (!skillMd.exists()) {
            throw new RuntimeException("SKILL.md not found in skill directory");
        }

        java.io.File scriptsDir = new java.io.File(skillDir, "scripts");
        if (!scriptsDir.exists() || !scriptsDir.isDirectory()) {
            throw new RuntimeException("scripts/ directory not found in skill directory");
        }

        log.info("Skill structure validation passed");
    }

    /**
     * 提取 Skill 名称
     */
    private String extractSkillName(String skillPath) {
        java.io.File skillDir = new java.io.File(skillPath);
        String dirName = skillDir.getName();

        // 如果目录名包含时间戳，提取实际的 skill 名称
        if (dirName.startsWith("skill-import-")) {
            // 从 SKILL.md 中提取名称
            java.io.File skillMd = new java.io.File(skillDir, "SKILL.md");
            try {
                String content = java.nio.file.Files.readString(skillMd.toPath());
                // 简单提取第一行作为名称
                String firstLine = content.lines().findFirst().orElse("");
                if (firstLine.startsWith("# ")) {
                    return firstLine.substring(2).trim().toLowerCase().replace(" ", "_");
                }
            } catch (Exception e) {
                log.warn("Failed to extract skill name from SKILL.md", e);
            }
        }

        return dirName;
    }

    /**
     * 移动到目标目录
     */
    private String moveToTargetDirectory(String sourcePath, String targetDirectory, String skillName) {
        try {
            // 如果未指定目标目录，使用默认目录
            if (targetDirectory == null || targetDirectory.isEmpty()) {
                targetDirectory = System.getProperty("user.home") + "/skills";
            }

            java.io.File targetDir = new java.io.File(targetDirectory);
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            String finalPath = targetDirectory + "/" + skillName;
            java.io.File finalDir = new java.io.File(finalPath);

            // 如果目标已存在，先删除
            if (finalDir.exists()) {
                deleteDirectory(finalDir);
            }

            // 移动目录
            java.io.File sourceDir = new java.io.File(sourcePath);
            if (!sourceDir.renameTo(finalDir)) {
                // 如果 rename 失败，尝试复制
                copyDirectory(sourceDir, finalDir);
                deleteDirectory(sourceDir);
            }

            log.info("Moved skill to: {}", finalPath);
            return finalPath;

        } catch (Exception e) {
            throw new RuntimeException("Failed to move skill to target directory: " + e.getMessage(), e);
        }
    }

    /**
     * 录入数据库
     */
    private void registerSkillInDatabase(String skillName, String skillPath) {
        Skill existingSkill = skillMapper.selectOne(
                new LambdaQueryWrapper<Skill>().eq(Skill::getSkillId, skillName)
        );

        if (existingSkill != null) {
            // 更新现有 Skill
            existingSkill.setPath(skillPath);
            existingSkill.setUpdatedAt(LocalDateTime.now());
            skillMapper.updateById(existingSkill);
            log.info("Updated existing skill in database: {}", skillName);
        } else {
            // 创建新 Skill
            Skill newSkill = new Skill();
            newSkill.setSkillId(skillName);
            newSkill.setName(skillName);
            newSkill.setPath(skillPath);
            newSkill.setEnabled(true);
            newSkill.setCreatedAt(LocalDateTime.now());
            newSkill.setUpdatedAt(LocalDateTime.now());
            skillMapper.insert(newSkill);
            log.info("Registered new skill in database: {}", skillName);
        }
    }

    /**
     * 删除目录
     */
    private void deleteDirectory(java.io.File directory) {
        if (directory.exists()) {
            java.io.File[] files = directory.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * 复制目录
     */
    private void copyDirectory(java.io.File source, java.io.File target) throws Exception {
        if (!target.exists()) {
            target.mkdirs();
        }

        java.io.File[] files = source.listFiles();
        if (files != null) {
            for (java.io.File file : files) {
                java.io.File targetFile = new java.io.File(target, file.getName());
                if (file.isDirectory()) {
                    copyDirectory(file, targetFile);
                } else {
                    java.nio.file.Files.copy(file.toPath(), targetFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
