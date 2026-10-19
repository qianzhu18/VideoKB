package com.example.server.entity;

import lombok.Data;

@Data
public class KnowledgeLexicalGeneration {
    private Long versionId;
    private Long sourceId;
    private Long ownerUserId;
    private Integer documentCount;
}
