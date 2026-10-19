package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_sources")
public class KnowledgeSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sourceType;
    private Long ownerUserId;
    private Long spaceId;
    private Long collectionId;
    private Long mediaId;
    private String title;
    private String contentHash;
    private Integer currentVersion;
    private String status;
    private String externalPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
