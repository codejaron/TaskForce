package com.agent.service;

import com.agent.api.dto.SkillDTO;
import com.agent.infrastructure.persistence.entity.Skill;
import com.agent.infrastructure.persistence.mapper.SkillMapper;
import com.agent.infrastructure.skill.DbFilteredSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Value("${skill.user-directory}")
    private String userSkillsDirectory;

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
     * 从本地文件夹导入 Skill
     * @param sourcePath 包含 skill 的文件夹绝对路径
     */
    @Transactional
    public void importFromFolder(String sourcePath) {
        log.info("Importing skill from folder: sourcePath={}", sourcePath);

        try {
            // 1. 验证源文件夹存在且可访问
            validateSourceFolder(sourcePath);

            // 2. 验证 skill 结构
            validateSkillStructure(sourcePath);

            // 3. 提取 skill 名称
            String skillName = extractSkillName(sourcePath);

            // 4. 复制到目标目录（总是使用复制模式）
            String finalPath = copyToTargetDirectory(sourcePath, skillName);

            // 5. 注册到数据库
            registerSkillInDatabase(skillName, finalPath);

            // 6. 重新加载 skill 注册表
            skillRegistry.reload();

            log.info("Successfully imported skill '{}' from folder: {}", skillName, sourcePath);

        } catch (Exception e) {
            log.error("Failed to import skill from folder: {}", sourcePath, e);
            throw new RuntimeException("Failed to import skill: " + e.getMessage(), e);
        }
    }

    /**
     * 从上传的文件导入 Skill
     * @param files 上传的文件（必须包含 SKILL.md）
     */
    @Transactional
    public void importFromUpload(MultipartFile[] files) {
        log.info("Importing skill from upload: {} files", files.length);

        if (files == null || files.length == 0) {
            throw new RuntimeException("No files uploaded");
        }

        try {
            // 1. 为上传的文件创建临时目录
            String tempDir = System.getProperty("java.io.tmpdir") + "/skill-upload-" + System.currentTimeMillis();
            File tempDirFile = new File(tempDir);
            tempDirFile.mkdirs();

            log.info("Saving uploaded files to temporary directory: {}", tempDir);

            // 2. 保存所有上传的文件到临时目录，保留文件夹结构
            for (MultipartFile file : files) {
                String originalFilename = file.getOriginalFilename();
                if (originalFilename == null || originalFilename.isEmpty()) {
                    continue;
                }

                // 处理来自 webkitdirectory 的相对路径
                // 浏览器发送的路径格式如 "skill-name/SKILL.md" 或 "skill-name/scripts/run.sh"
                Path filePath = Paths.get(tempDir, originalFilename);
                File targetFile = filePath.toFile();

                // 如果需要，创建父目录
                File parentDir = targetFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                // 保存文件
                file.transferTo(targetFile);
                log.debug("Saved file: {}", originalFilename);
            }

            // 3. 查找 skill 根目录
            // 上传的文件可能在子目录中（例如 temp/skill-name/SKILL.md）
            String skillRootPath = findSkillRootDirectory(tempDir);

            // 4. 验证 skill 结构
            validateSkillStructure(skillRootPath);

            // 5. 提取 skill 名称
            String skillName = extractSkillName(skillRootPath);

            // 6. 复制到目标目录
            String finalPath = copyToTargetDirectory(skillRootPath, skillName);

            // 7. 注册到数据库
            registerSkillInDatabase(skillName, finalPath);

            // 8. 重新加载 skill 注册表
            skillRegistry.reload();

            // 9. 清理临时目录
            deleteDirectory(tempDirFile);

            log.info("Successfully imported skill '{}' from upload", skillName);

        } catch (Exception e) {
            log.error("Failed to import skill from upload", e);
            throw new RuntimeException("Failed to import skill: " + e.getMessage(), e);
        }
    }

    /**
     * 在上传的文件中查找 skill 根目录
     * 处理文件上传时带有父文件夹的情况
     */
    private String findSkillRootDirectory(String tempDir) {
        File tempDirFile = new File(tempDir);

        // 检查 SKILL.md 是否直接在临时目录中
        File skillMd = new File(tempDirFile, "SKILL.md");
        if (skillMd.exists()) {
            return tempDir;
        }

        // 检查是否有单个子目录包含 SKILL.md
        File[] subdirs = tempDirFile.listFiles(File::isDirectory);
        if (subdirs != null && subdirs.length == 1) {
            File subdir = subdirs[0];
            skillMd = new File(subdir, "SKILL.md");
            if (skillMd.exists()) {
                return subdir.getAbsolutePath();
            }
        }

        // 如果未找到，返回临时目录，让验证失败并给出清晰的错误信息
        return tempDir;
    }

    /**
     * 验证源文件夹存在且可访问
     * @param sourcePath 源文件夹路径
     */
    private void validateSourceFolder(String sourcePath) {
        File sourceDir = new File(sourcePath);

        // 检查是否存在
        if (!sourceDir.exists()) {
            throw new RuntimeException("Source folder does not exist: " + sourcePath);
        }

        // 检查是否为目录
        if (!sourceDir.isDirectory()) {
            throw new RuntimeException("Source path is not a directory: " + sourcePath);
        }

        // 检查是否可读
        if (!sourceDir.canRead()) {
            throw new RuntimeException("Source folder is not readable: " + sourcePath);
        }

        // 安全检查：验证绝对路径
        if (!sourceDir.isAbsolute()) {
            throw new RuntimeException("Source path must be absolute: " + sourcePath);
        }

        // 安全检查：防止路径遍历攻击
        try {
            String canonicalPath = sourceDir.getCanonicalPath();
            if (canonicalPath.contains("..")) {
                throw new RuntimeException("Invalid path: path traversal detected");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to validate path: " + e.getMessage(), e);
        }
    }

    /**
     * 复制文件夹到目标目录
     * @param sourcePath 源文件夹路径
     * @param skillName 提取的 skill 名称
     * @return 导入的 skill 最终路径
     */
    private String copyToTargetDirectory(String sourcePath, String skillName) {
        try {
            // 使用配置的默认目录
            String targetDirectory = userSkillsDirectory;

            // 如果目标目录不存在，创建它
            File targetDir = new File(targetDirectory);
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            // 构建最终路径
            String finalPath = targetDirectory + "/" + skillName;
            File finalDir = new File(finalPath);

            // 如果已存在同名 skill，先删除
            if (finalDir.exists()) {
                log.info("Deleting existing skill at: {}", finalPath);
                deleteDirectory(finalDir);
            }

            // 复制目录
            File sourceDir = new File(sourcePath);
            log.info("Copying skill from {} to {}", sourcePath, finalPath);
            copyDirectory(sourceDir, finalDir);

            return finalPath;

        } catch (Exception e) {
            throw new RuntimeException("Failed to copy skill to target directory: " + e.getMessage(), e);
        }
    }

    /**
     * 验证 Skill 结构
     * 必需：SKILL.md
     * 可选：scripts/, references/, assets/
     */
    private void validateSkillStructure(String skillPath) {
        java.io.File skillDir = new java.io.File(skillPath);
        if (!skillDir.exists() || !skillDir.isDirectory()) {
            throw new RuntimeException("Skill directory does not exist: " + skillPath);
        }

        // 检查必需的文件：SKILL.md
        java.io.File skillMd = new java.io.File(skillDir, "SKILL.md");
        if (!skillMd.exists()) {
            throw new RuntimeException("SKILL.md not found in skill directory");
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
