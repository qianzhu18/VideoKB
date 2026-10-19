package com.example.server.dto;

import com.example.server.entity.KnowledgeSpace;

import java.time.LocalDateTime;

public record KnowledgeSpaceView(
        Long id,
        String name,
        String description,
        boolean systemDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static KnowledgeSpaceView from(KnowledgeSpace space) {
        return new KnowledgeSpaceView(
                space.getId(),
                space.getName(),
                space.getDescription(),
                Boolean.TRUE.equals(space.getSystemDefault()),
                space.getCreatedAt(),
                space.getUpdatedAt());
    }
}
