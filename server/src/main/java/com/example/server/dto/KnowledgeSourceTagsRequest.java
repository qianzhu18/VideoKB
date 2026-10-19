package com.example.server.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Replace-the-whole-set semantics: the requested list becomes the source's tag set. */
public record KnowledgeSourceTagsRequest(
        @NotNull(message = "标签列表不能为空")
        List<String> tags
) {
}
