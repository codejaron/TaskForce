package com.agent.infrastructure.persistence.mapper;

import com.agent.infrastructure.persistence.entity.Skill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Skill Mapper
 */
@Mapper
public interface SkillMapper extends BaseMapper<Skill> {
}
