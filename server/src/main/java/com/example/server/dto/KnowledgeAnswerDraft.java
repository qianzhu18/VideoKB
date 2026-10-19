package com.example.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Untrusted structured model output. {@link com.example.server.service.KnowledgeAnswerService}
 * validates every cited segment and quote before it becomes a public answer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeAnswerDraft(
        String answerability,
        String answer,
        List<CitationDraft> citations
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CitationDraft(String segmentId, String claim, String quote) {
    }
}
