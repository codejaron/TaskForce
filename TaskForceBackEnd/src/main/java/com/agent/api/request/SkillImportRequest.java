package com.agent.api.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * Skill 文件夹导入请求
 */
@Data
public class SkillImportRequest {

    @NotBlank(message = "Source folder path cannot be blank")
    private String sourcePath;  // 包含 skill 的文件夹绝对路径
}
