package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Authoritative evidence row for one indexed time range of a media asset. MySQL owns the
 * text; Qdrant only carries the vector plus filter fields, so every recalled hit is
 * backfilled from here.
 */
@Data
@TableName("knowledge_segments")
public class KnowledgeSegment {

    /** UUID string; also serves as the Qdrant point id for in-place rebuilds. */
    @TableId(type = IdType.INPUT)
    private String id;

    private Long sourceId;
    private Long versionId;
    private Long mediaId;
    private Long startMs;
    private Long endMs;
    private String transcript;
    private String ocrText;
    private String summary;
    private String contentHash;
    private String metadata;
    private LocalDateTime createdAt;
}
