package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A grounded, cross-video question. Retrieval and answering share the same space and
 * collection filters so an answer can never silently cross a user's knowledge boundary.
 */
public record KnowledgeAskRequest(
        Long spaceId,
        Long collectionId,
        @NotBlank(message = "问题不能为空")
        String query,
        /** Number of evidence units exposed to the answer model; clamped by retrieval. */
        Integer topK,
        /** "vector", "keyword" or "hybrid"; null defaults to hybrid. */
        String strategy
) {
    public KnowledgeSearchRequest toSearchRequest() {
        return new KnowledgeSearchRequest(spaceId, collectionId, query, topK, strategy);
    }
}
