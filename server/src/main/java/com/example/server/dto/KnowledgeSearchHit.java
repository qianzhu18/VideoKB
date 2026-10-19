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
        String matchType,
        Long versionId,
        String indexProfile,
        java.util.List<KnowledgeEvidence> evidence
) {
    public KnowledgeSearchHit(String segmentId, Long sourceId, String sourceType, Long mediaId, String title,
                              long startMs, long endMs, double score, String transcript, String ocrText,
                              String summary, String matchType) {
        this(segmentId,sourceId,sourceType,mediaId,title,startMs,endMs,score,transcript,ocrText,summary,matchType,
                null,"legacy-v1",java.util.List.of(new KnowledgeEvidence(segmentId,sourceId,null,mediaId,startMs,endMs,transcript,ocrText)));
    }
}
