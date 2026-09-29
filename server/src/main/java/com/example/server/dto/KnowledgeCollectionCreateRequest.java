package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeCollectionCreateRequest(
        Long parentId,
        @NotBlank(message = "目录名称不能为空")
        @Size(max = 100, message = "目录名称不能超过 100 个字符")
        String name,
        Integer sortOrder
) {
}
