package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("knowledge_embedding_cache")
public class KnowledgeEmbeddingCache {
    @TableId(type = IdType.INPUT) private String cacheKey;
    private Long ownerUserId;
    private String model;
    private String indexProfile;
    private String textHash;
    private Integer vectorDimension;
    private String vectorJson;
}
