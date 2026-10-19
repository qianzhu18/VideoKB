package com.example.server.dto;

import jakarta.validation.constraints.NotNull;

/** A source belongs to exactly one space and may optionally sit in one directory. */
public record KnowledgeSourceLocationRequest(
        @NotNull(message = "知识空间不能为空")
        Long spaceId,
        Long collectionId
) {
}
