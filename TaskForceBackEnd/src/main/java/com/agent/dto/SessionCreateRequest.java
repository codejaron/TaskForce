package com.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Session 创建请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCreateRequest {
    
    private String name;
    
    private String type; // SINGLE/GROUP
    
    private List<Long> agentIds; // 参与的智能体ID列表
    
    private Integer maxRounds;
    
    private String config; // JSON配置
}
