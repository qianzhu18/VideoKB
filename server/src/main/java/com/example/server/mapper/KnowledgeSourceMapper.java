package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeSourceMapper extends BaseMapper<KnowledgeSource> {
    @Select("SELECT * FROM knowledge_sources WHERE id = #{id} FOR UPDATE")
    KnowledgeSource lockById(@Param("id") Long id);
}
