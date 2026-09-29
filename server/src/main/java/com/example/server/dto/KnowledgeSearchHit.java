package com.example.server.dto;

/**
 * One recalled evidence unit: authoritative text backfilled from knowledge_segments plus
 * the metadata needed to locate the moment in the original video.
 */
public record KnowledgeSearchHit(
        String segmentId,
        Long sourceId,
        String sourceType,
        Long mediaId,
        String title,
        long startMs,
        long endMs,
        double score,
        String transcript,
        String ocrText,
        String summary,
        String matchType
) {
}
