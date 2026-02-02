package com.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.infrastructure.persistence.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
    
    /**
     * 追加内容（增量更新）
     */
    @Update("UPDATE messages SET content = CONCAT(IFNULL(content, ''), #{delta}) WHERE id = #{messageId}")
    void appendContent(@Param("messageId") Long messageId, @Param("delta") String delta);
}
