package com.agent.api.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * Skill Git 导入请求
 */
@Data
public class SkillImportRequest {

    @NotBlank(message = "Git URL cannot be blank")
    private String gitUrl;

    private String branch;

    private String targetDirectory;
}
