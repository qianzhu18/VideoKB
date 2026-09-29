package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Cross-video retrieval inside one knowledge space. Empty results mean "no supporting
 * evidence found" — callers must not invent conclusions.
 *
 * @param strategy recall strategy: "vector", "keyword" or "hybrid" (RRF fusion); null
 *                 defaults to hybrid so the comparison in the evaluation harness can flip
 *                 one switch without changing defaults anywhere else.
 */
public record KnowledgeSearchRequest(
        @NotNull(message = "知识空间不能为空")
        Long spaceId,
        Long collectionId,
        @NotBlank(message = "检索问题不能为空")
        String query,
        Integer topK,
        String strategy
) {
}
