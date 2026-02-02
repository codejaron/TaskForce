package com.agent.infrastructure.persistence.mapper;

import com.agent.infrastructure.persistence.entity.SessionArtifact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Session Artifact Mapper
 * 提供对 session_artifact 表的数据访问
 */
@Mapper
public interface SessionArtifactMapper extends BaseMapper<SessionArtifact> {
}
