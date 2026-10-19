package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("knowledge_retrieval_blocks")
public class KnowledgeRetrievalBlock {
    @TableId(type = IdType.INPUT) private String id;
    private Long sourceId;
    private Long versionId;
    private Long mediaId;
    private Long startMs;
    private Long endMs;
    private String text;
    private String indexProfile;
    private String textHash;
}
