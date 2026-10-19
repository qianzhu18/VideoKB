package com.example.server.mapper;

import com.example.server.entity.KnowledgeLexicalGeneration;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface KnowledgeLexicalGenerationMapper {
    @Select("""
        <script>SELECT version_id, source_id, owner_user_id, document_count
        FROM knowledge_lexical_generations WHERE backend_key=#{backend} AND version_id IN
        <foreach collection='versions' item='version' open='(' separator=',' close=')'>#{version}</foreach>
        </script>
        """)
    List<KnowledgeLexicalGeneration> find(@Param("backend") String backend, @Param("versions") List<Long> versions);

    @Insert("""
        INSERT INTO knowledge_lexical_generations(backend_key, version_id, source_id, owner_user_id, document_count)
        VALUES(#{backend}, #{version}, #{source}, #{owner}, #{count})
        ON DUPLICATE KEY UPDATE source_id=VALUES(source_id), owner_user_id=VALUES(owner_user_id),
            document_count=VALUES(document_count), indexed_at=CURRENT_TIMESTAMP(3)
        """)
    int complete(@Param("backend") String backend, @Param("version") Long version,
                 @Param("source") Long source, @Param("owner") Long owner, @Param("count") int count);
}
