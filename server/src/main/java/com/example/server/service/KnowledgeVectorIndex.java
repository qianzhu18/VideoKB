package com.example.server.service;

import java.util.List;

/** Retrieval depends on a generation scope, not an engine-specific folder payload. */
public interface KnowledgeVectorIndex {
    record Hit(String segmentId, Long sourceId, double score) {}
    List<Hit> search(List<Double> vector, KnowledgeQueryScope scope, int limit);
}
