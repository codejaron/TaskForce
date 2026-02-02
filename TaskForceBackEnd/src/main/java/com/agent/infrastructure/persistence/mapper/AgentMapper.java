package com.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.infrastructure.persistence.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能体 Mapper
 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
}
