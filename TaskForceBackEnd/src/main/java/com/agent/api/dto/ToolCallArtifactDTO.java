package com.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用产物 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallArtifactDTO {

    private String artifactId;
    private String name;
    private String filePath;
    private String syncStatus;
    private Long sizeBytes;
    private String downloadPath;
}
