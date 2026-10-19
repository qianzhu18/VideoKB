package com.example.server.dto;

import jakarta.validation.constraints.Size;

public record KnowledgeCollectionUpdateRequest(
        Long parentId,
        Boolean moveToRoot,
        @Size(max = 100, message = "目录名称不能超过 100 个字符")
        String name,
        Integer sortOrder
) {
}
