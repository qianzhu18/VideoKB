package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One local-directory ingest request. The root path must sit inside a configured allowed
 * root; anything else is rejected before any I/O happens.
 *
 * @param analyze false to register and host the files WITHOUT dispatching the ASR
 *                pipeline — for assets whose transcript will be imported separately
 *                (e.g. migrated checkpoints); null defaults to true
 */
public record KnowledgeIngestRequest(
        @NotBlank(message = "导入目录不能为空")
        String rootPath,
        @NotNull(message = "目标知识空间不能为空")
        Long spaceId,
        Long collectionId,
        Boolean dryRun,
        Boolean analyze
) {
}
