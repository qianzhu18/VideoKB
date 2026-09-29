package com.example.server.dto;

import com.example.server.entity.KnowledgeSegment;

/**
 * External-facing projection of a segment row: text evidence with its time window.
 * Deliberately excludes internal fields (versionId, contentHash, metadata) so the
 * MCP adapter and any other external consumer only see what citing evidence needs.
 */
public record KnowledgeSegmentView(
        String id,
        Long mediaId,
        Long startMs,
        Long endMs,
        String transcript,
        String ocrText,
        String summary) {

    public static KnowledgeSegmentView from(KnowledgeSegment segment) {
        return new KnowledgeSegmentView(
                segment.getId(),
                segment.getMediaId(),
                segment.getStartMs(),
                segment.getEndMs(),
                segment.getTranscript(),
                segment.getOcrText(),
                segment.getSummary());
    }
}
