package com.example.server.dto;

import com.example.server.entity.KnowledgeLink;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;

import java.time.LocalDateTime;

/**
 * One link with both sides' evidence: the script paragraph and the video segment it
 * points at, so the UI can let the user judge a suggestion without extra fetches.
 */
public record KnowledgeLinkView(
        Long id,
        String status,
        String linkType,
        Double confidence,
        String sourceSegmentId,
        String sourceExcerpt,
        Long targetSourceId,
        String targetTitle,
        String targetSourceType,
        Long targetMediaId,
        Long targetStartMs,
        Long targetEndMs,
        String targetExcerpt,
        LocalDateTime updatedAt) {

    private static final int EXCERPT_CHARS = 160;

    public static KnowledgeLinkView from(KnowledgeLink link, KnowledgeSegment from,
                                         KnowledgeSegment to, KnowledgeSource target) {
        return new KnowledgeLinkView(
                link.getId(),
                link.getStatus(),
                link.getLinkType(),
                link.getConfidence() == null ? null : link.getConfidence().doubleValue(),
                link.getSourceSegmentId(),
                excerpt(from.getTranscript()),
                link.getTargetSourceId(),
                target.getTitle(),
                target.getSourceType(),
                to.getMediaId(),
                to.getStartMs(),
                to.getEndMs(),
                excerpt(firstNonBlank(to.getTranscript(), to.getOcrText(), to.getSummary())),
                link.getUpdatedAt());
    }

    private static String excerpt(String value) {
        if (value == null) return null;
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= EXCERPT_CHARS ? normalized
                : normalized.substring(0, EXCERPT_CHARS) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
