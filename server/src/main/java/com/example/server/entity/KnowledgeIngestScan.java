package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable record of one local-directory ingest scan (the import manifest). */
@Data
@TableName("knowledge_ingest_scans")
public class KnowledgeIngestScan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private String rootPath;
    private Boolean dryRun;
    private Integer createdCount;
    private Integer changedCount;
    private Integer movedCount;
    private Integer deletedCount;
    private Integer unchangedCount;
    private Integer errorCount;
    private String plan;
    private LocalDateTime createdAt;
}
