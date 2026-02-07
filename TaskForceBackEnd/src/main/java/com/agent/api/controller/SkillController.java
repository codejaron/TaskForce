package com.agent.api.controller;

import com.agent.api.dto.SkillDTO;
import com.agent.api.request.SkillImportRequest;
import com.agent.api.response.ApiResponse;
import com.agent.service.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Skill 管理 Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 列出所有 Skill
     */
    @GetMapping
    public ApiResponse<List<SkillDTO>> listAll() {
        log.info("Listing all skills");
        List<SkillDTO> skills = skillService.listAll();
        return ApiResponse.success(skills);
    }

    /**
     * 获取 Skill 详情
     */
    @GetMapping("/{skillId}")
    public ApiResponse<SkillDTO> getById(@PathVariable String skillId) {
        log.info("Getting skill by id: {}", skillId);
        SkillDTO skill = skillService.getById(skillId);
        return ApiResponse.success(skill);
    }

    /**
     * 启用 Skill
     */
    @PostMapping("/{skillId}/enable")
    public ApiResponse<Void> enable(@PathVariable String skillId) {
        log.info("Enabling skill: {}", skillId);
        skillService.enable(skillId);
        return ApiResponse.success(null);
    }

    /**
     * 禁用 Skill
     */
    @PostMapping("/{skillId}/disable")
    public ApiResponse<Void> disable(@PathVariable String skillId) {
        log.info("Disabling skill: {}", skillId);
        skillService.disable(skillId);
        return ApiResponse.success(null);
    }

    /**
     * 从 Git 导入 Skill
     */
    @PostMapping("/import")
    public ApiResponse<Void> importFromGit(@Validated @RequestBody SkillImportRequest request) {
        log.info("Importing skill from Git: {}", request.getGitUrl());
        skillService.importFromGit(
                request.getGitUrl(),
                request.getBranch(),
                request.getTargetDirectory()
        );
        return ApiResponse.success(null);
    }
}
