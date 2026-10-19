package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_ingest_jobs")
public class KnowledgeIngestJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerUserId;
    private Long sourceId;
    private Long mediaId;
    private String state;
    private String stage;
    private Integer attemptCount;
    private Boolean forceRebuild;
    private String errorMessage;
    private LocalDateTime nextDispatchAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
