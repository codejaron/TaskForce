package com.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill DTO
 */
@Data
public class SkillDTO {
    private Long id;
    private String skillId;
    private String name;
    private String path;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
