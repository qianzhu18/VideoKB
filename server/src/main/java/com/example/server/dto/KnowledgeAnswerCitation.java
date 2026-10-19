package com.example.server.dto;

/**
 * A citation returned by {@code /knowledge/ask}. Source metadata is populated by the
 * server from an actually retrieved segment, never trusted from model output.
 */
public record KnowledgeAnswerCitation(
        String segmentId,
        Long sourceId,
        Long mediaId,
        String title,
        long startMs,
        long endMs,
        String claim,
        String quote,
        double retrievalScore,
        Long versionId
) {
    public KnowledgeAnswerCitation(String segmentId, Long sourceId, Long mediaId, String title,
                                   long startMs, long endMs, String claim, String quote, double retrievalScore) {
        this(segmentId,sourceId,mediaId,title,startMs,endMs,claim,quote,retrievalScore,null);
    }
}
