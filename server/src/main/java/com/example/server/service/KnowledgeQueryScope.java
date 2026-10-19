package com.example.server.service;

import java.util.Map;

/** A snapshot of readable source generations, resolved identically for Web and MCP. */
public record KnowledgeQueryScope(Long userId, Map<Long, Generation> generations) {
    public record Generation(Long versionId, int versionNo) {}
    public boolean contains(Long sourceId, Long versionId) {
        var generation = generations.get(sourceId);
        return generation != null && generation.versionId().equals(versionId);
    }
}
