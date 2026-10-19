package com.example.server.dto;
import com.example.server.entity.KnowledgeSegment;

/** Exact original ASR/OCR window. No synthesized sub-window timestamps. */
public record KnowledgeEvidence(String segmentId, Long sourceId, Long versionId, Long mediaId,
                                long startMs, long endMs, String transcript, String ocrText) {
    public static KnowledgeEvidence from(KnowledgeSegment raw) {
        return new KnowledgeEvidence(raw.getId(), raw.getSourceId(), raw.getVersionId(), raw.getMediaId(),
                raw.getStartMs(), raw.getEndMs(), raw.getTranscript(), raw.getOcrText());
    }
}
