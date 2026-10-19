package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface KnowledgeRetrievalBlockMapper extends BaseMapper<KnowledgeRetrievalBlock> {
    @Insert("INSERT INTO knowledge_block_evidence(block_id,evidence_id,ordinal_no) VALUES(#{block},#{evidence},#{ordinal})")
    int link(@Param("block") String block, @Param("evidence") String evidence, @Param("ordinal") int ordinal);
    @Select("SELECT s.* FROM knowledge_segments s JOIN knowledge_block_evidence e ON e.evidence_id=s.id JOIN knowledge_retrieval_blocks b ON b.id=e.block_id AND b.version_id=s.version_id AND b.source_id=s.source_id WHERE e.block_id=#{block} ORDER BY e.ordinal_no")
    List<KnowledgeSegment> evidence(@Param("block") String block);
}
