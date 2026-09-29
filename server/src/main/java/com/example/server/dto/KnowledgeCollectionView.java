package com.example.server.dto;

import com.example.server.entity.KnowledgeCollection;

import java.time.LocalDateTime;

public record KnowledgeCollectionView(
        Long id,
        Long spaceId,
        Long parentId,
        String name,
        String path,
        int sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static KnowledgeCollectionView from(KnowledgeCollection collection) {
        return new KnowledgeCollectionView(
                collection.getId(),
                collection.getSpaceId(),
                collection.getParentId(),
                collection.getName(),
                collection.getPath(),
                collection.getSortOrder() == null ? 0 : collection.getSortOrder(),
                collection.getCreatedAt(),
                collection.getUpdatedAt());
    }
}
