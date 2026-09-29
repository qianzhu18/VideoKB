package com.example.server.dto;

import com.example.server.entity.KnowledgeSource;

import java.time.LocalDateTime;
import java.util.List;

public record KnowledgeSourceView(
        Long id,
        String sourceType,
        Long spaceId,
        Long collectionId,
        Long mediaId,
        String title,
        String status,
        int currentVersion,
        List<String> tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static KnowledgeSourceView from(KnowledgeSource source) {
        return from(source, List.of());
    }

    public static KnowledgeSourceView from(KnowledgeSource source, List<String> tags) {
        return new KnowledgeSourceView(
                source.getId(),
                source.getSourceType(),
                source.getSpaceId(),
                source.getCollectionId(),
                source.getMediaId(),
                source.getTitle(),
                source.getStatus(),
                source.getCurrentVersion() == null ? 0 : source.getCurrentVersion(),
                tags == null ? List.of() : List.copyOf(tags),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }
}
