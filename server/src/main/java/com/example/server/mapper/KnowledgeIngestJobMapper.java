package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeIngestJob;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface KnowledgeIngestJobMapper extends BaseMapper<KnowledgeIngestJob> {
    @Insert("""
            INSERT INTO knowledge_ingest_jobs (owner_user_id, source_id, media_id)
            VALUES (#{owner}, #{source}, #{media}) ON DUPLICATE KEY UPDATE media_id = media_id
            """)
    int enqueue(@Param("owner") Long owner, @Param("source") Long source, @Param("media") Long media);

    @Select("SELECT * FROM knowledge_ingest_jobs WHERE state = 'QUEUED' AND next_dispatch_at <= CURRENT_TIMESTAMP(3) ORDER BY next_dispatch_at LIMIT 20")
    List<KnowledgeIngestJob> dispatchable();

    @Update("""
            UPDATE knowledge_ingest_jobs SET next_dispatch_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(3))
            WHERE id = #{id} AND state = 'QUEUED' AND next_dispatch_at <= CURRENT_TIMESTAMP(3)
            """)
    int reserveDispatch(@Param("id") Long id);

    @Update("""
            UPDATE knowledge_ingest_jobs SET state = 'PROCESSING', stage = 'EXTRACTING',
            attempt_count = attempt_count + 1, heartbeat_at = CURRENT_TIMESTAMP(3), error_message = NULL
            WHERE id = #{id} AND state = 'QUEUED' AND attempt_count < 3
            """)
    int start(@Param("id") Long id);

    @Update("""
            UPDATE knowledge_ingest_jobs SET state = CASE WHEN attempt_count >= 3 THEN 'FAILED' ELSE 'QUEUED' END,
            stage = 'RECOVERING', error_message = 'Worker interrupted; recovered from persisted job',
            next_dispatch_at = CURRENT_TIMESTAMP(3)
            WHERE state = 'PROCESSING' AND heartbeat_at < TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP(3))
            """)
    int recoverInterrupted();

    @Update("""
            UPDATE knowledge_ingest_jobs SET heartbeat_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND state = 'PROCESSING' AND attempt_count = #{attempt}
            """)
    int heartbeat(@Param("id") Long id, @Param("attempt") int attempt);
}
