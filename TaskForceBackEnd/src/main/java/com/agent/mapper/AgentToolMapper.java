package com.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.entity.AgentTool;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent工具关联 Mapper
 */
@Mapper
public interface AgentToolMapper extends BaseMapper<AgentTool> {
}
