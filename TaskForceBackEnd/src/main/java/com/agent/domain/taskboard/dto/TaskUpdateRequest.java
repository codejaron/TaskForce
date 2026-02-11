package com.agent.domain.taskboard.dto;

import com.agent.domain.taskboard.model.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务更新请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskUpdateRequest {

    /**
     * 任务主题
     */
    private String subject;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 任务状态
     */
    private TaskStatus status;

    /**
     * 任务所有者
     */
    private String owner;

    /**
     * 被阻塞的任务列表
     */
    private List<String> blockedBy;

    /**
     * 阻塞的任务列表
     */
    private List<String> blocks;
}
