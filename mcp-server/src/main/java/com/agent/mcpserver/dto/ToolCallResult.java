package com.agent.mcpserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工具调用结果 DTO
 * 符合 MCP 协议规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCallResult {

    /**
     * 结果内容列表
     */
    private List<Content> content;

    /**
     * 是否错误
     */
    @Builder.Default
    private Boolean isError = false;

    /**
     * 内容项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {
        /**
         * 内容类型（text, image, resource）
         */
        private String type;

        /**
         * 文本内容
         */
        private String text;

        /**
         * 图片数据（Base64 或 URL）
         */
        private String data;

        /**
         * MIME 类型
         */
        private String mimeType;
    }

    /**
     * 创建文本结果
     */
    public static ToolCallResult text(String text) {
        return ToolCallResult.builder()
                .content(List.of(Content.builder()
                        .type("text")
                        .text(text)
                        .build()))
                .build();
    }

    /**
     * 创建错误结果
     */
    public static ToolCallResult error(String message) {
        return ToolCallResult.builder()
                .content(List.of(Content.builder()
                        .type("text")
                        .text(message)
                        .build()))
                .isError(true)
                .build();
    }
}
