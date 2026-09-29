package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_source_versions")
public class KnowledgeSourceVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceId;
    private Integer versionNo;
    private String contentHash;
    private String parserVersion;
    private String embeddingModel;
    private String status;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
