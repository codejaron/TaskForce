package com.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.entity.SessionAgent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话智能体关联表 Mapper
 */
@Mapper
public interface SessionAgentMapper extends BaseMapper<SessionAgent> {
}
