package com.agent.domain.execution.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionState {

    private String instanceId;

    private AgentExecutionStatus status;

    private String detail;

    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
