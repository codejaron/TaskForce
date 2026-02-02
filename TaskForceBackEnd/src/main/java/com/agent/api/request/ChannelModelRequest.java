package com.agent.api.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelModelRequest {
    private Long id;

    @NotBlank(message = "modelValue 不能为空")
    private String modelValue; // API 调用时的真实模型标识，例如 gpt-4o

    @NotBlank(message = "displayName 不能为空")
    private String displayName; // 给用户看的名字
}

