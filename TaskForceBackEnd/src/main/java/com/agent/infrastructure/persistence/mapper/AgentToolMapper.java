package com.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.infrastructure.persistence.entity.AgentTool;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent工具关联 Mapper
 */
@Mapper
public interface AgentToolMapper extends BaseMapper<AgentTool> {
}
