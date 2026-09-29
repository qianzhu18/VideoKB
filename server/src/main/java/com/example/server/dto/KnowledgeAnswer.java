package com.example.server.dto;

import java.util.List;

/** A generated answer plus evidence that clients can turn into video seek links. */
public record KnowledgeAnswer(
        String answerability,
        String answer,
        List<KnowledgeAnswerCitation> citations,
        List<String> warnings
) {
    public static final String SUPPORTED = "SUPPORTED";
    public static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
}
