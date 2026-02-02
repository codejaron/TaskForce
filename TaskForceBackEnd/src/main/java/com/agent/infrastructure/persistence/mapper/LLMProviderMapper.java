package com.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.infrastructure.persistence.entity.LLMProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM渠道 Mapper
 */
@Mapper
public interface LLMProviderMapper extends BaseMapper<LLMProvider> {
}
