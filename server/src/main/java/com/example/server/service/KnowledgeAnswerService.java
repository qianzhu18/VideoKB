package com.example.server.service;

import com.example.server.dto.KnowledgeAnswer;
import com.example.server.dto.KnowledgeAnswerCitation;
import com.example.server.dto.KnowledgeAnswerDraft;
import com.example.server.dto.KnowledgeAskRequest;
import com.example.server.dto.KnowledgeSearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Cross-video RAG answer boundary. The model receives only retrieved evidence; its output is
 * accepted only when every citation names a retrieved segment and quotes that segment verbatim.
 */
@Service
public class KnowledgeAnswerService {

    private static final String NO_EVIDENCE_MESSAGE = "当前知识库中没有找到足以支持这个回答的证据。";
    private static final String INVALID_CITATION_MESSAGE = "模型回答缺少可验证的原始视频引用，已拒绝返回未经证据支持的结论。";

    private final KnowledgeSearchService searchService;
    private final KnowledgeAnswerGenerator answerGenerator;

    public KnowledgeAnswerService(KnowledgeSearchService searchService,
                                  KnowledgeAnswerGenerator answerGenerator) {
        this.searchService = searchService;
        this.answerGenerator = answerGenerator;
    }

    public KnowledgeAnswer ask(Long userId, KnowledgeAskRequest request) {
        List<KnowledgeSearchHit> hits = searchService.search(userId, request.toSearchRequest());
        if (hits.isEmpty()) {
            return insufficient(NO_EVIDENCE_MESSAGE, List.of("未检索到候选证据，未调用生成模型。"));
        }

        KnowledgeAnswerDraft draft = answerGenerator.generate(request.query().trim(), hits);
        return validateDraft(draft, hits);
    }

    public KnowledgeAnswer askStreaming(Long userId, KnowledgeAskRequest request,
                                        Consumer<String> phase, Consumer<String> answerDelta) {
        phase.accept("retrieving");
        List<KnowledgeSearchHit> hits = searchService.search(userId, request.toSearchRequest());
        if (hits.isEmpty()) {
            phase.accept("verifying");
            return insufficient(NO_EVIDENCE_MESSAGE, List.of("未检索到候选证据，未调用生成模型。"));
        }
        phase.accept("generating");
        KnowledgeAnswerDraft draft = answerGenerator.generateStreaming(request.query().trim(), hits, answerDelta);
        phase.accept("verifying");
        return validateDraft(draft, hits);
    }

    private KnowledgeAnswer validateDraft(KnowledgeAnswerDraft draft, List<KnowledgeSearchHit> hits) {
        List<KnowledgeAnswerCitation> citations = validateCitations(draft, hits);
        if (draft == null || !KnowledgeAnswer.SUPPORTED.equalsIgnoreCase(trim(draft.answerability()))
                || isBlank(draft.answer()) || citations.isEmpty()) {
            return insufficient(INVALID_CITATION_MESSAGE,
                    List.of("仅返回带有服务端验证的 segmentId 和原文 quote 的回答。",
                            refusalReason(draft, citations)));
        }
        return new KnowledgeAnswer(KnowledgeAnswer.SUPPORTED, draft.answer().trim(), citations, List.of());
    }

    private List<KnowledgeAnswerCitation> validateCitations(KnowledgeAnswerDraft draft,
                                                              List<KnowledgeSearchHit> hits) {
        if (draft == null || draft.citations() == null || draft.citations().isEmpty()) return List.of();
        Map<String, KnowledgeSearchHit> bySegmentId = new LinkedHashMap<>();
        for (KnowledgeSearchHit hit : hits) bySegmentId.put(hit.segmentId(), hit);

        List<KnowledgeAnswerCitation> verified = new ArrayList<>();
        for (KnowledgeAnswerDraft.CitationDraft citation : draft.citations()) {
            if (citation == null || isBlank(citation.segmentId()) || isBlank(citation.claim())
                    || !isVerbatimQuote(citation.quote(), bySegmentId.get(citation.segmentId()))) {
                continue;
            }
            KnowledgeSearchHit hit = bySegmentId.get(citation.segmentId());
            verified.add(new KnowledgeAnswerCitation(
                    hit.segmentId(), hit.sourceId(), hit.mediaId(), hit.title(), hit.startMs(), hit.endMs(),
                    citation.claim().trim(), citation.quote().trim(), hit.score()));
        }
        return verified;
    }

    private boolean isVerbatimQuote(String quote, KnowledgeSearchHit hit) {
        if (hit == null || isBlank(quote)) return false;
        String normalizedQuote = normalize(quote);
        if (normalizedQuote.length() < 4) return false;
        String evidence = normalize(String.join("\n", nonNull(hit.transcript()), nonNull(hit.ocrText()),
                nonNull(hit.summary())));
        return evidence.contains(normalizedQuote);
    }

    private KnowledgeAnswer insufficient(String message, List<String> warnings) {
        return new KnowledgeAnswer(KnowledgeAnswer.INSUFFICIENT_EVIDENCE, message, List.of(), warnings);
    }

    /** Distinguishes the three failure modes so eval reports can attribute misses. */
    private static String refusalReason(KnowledgeAnswerDraft draft, List<KnowledgeAnswerCitation> verified) {
        if (draft == null) {
            return "生成模型无有效输出。";
        }
        if (!KnowledgeAnswer.SUPPORTED.equalsIgnoreCase(trim(draft.answerability()))) {
            return "模型自身判定证据不足。";
        }
        int submitted = draft.citations() == null ? 0 : draft.citations().size();
        if (submitted == 0) {
            return "模型返回 SUPPORTED 但未提供任何引用（空 citations）。";
        }
        return "模型提交 " + submitted + " 条引用，" + verified.size() + " 条通过逐字校验。";
    }

    private static String normalize(String value) {
        // Case-folded: ASR transcripts render English in lowercase ("g c roots") while
        // the model quotes the canonical form ("GC Roots") — same characters, and for a
        // Chinese-dominant corpus the case distinction only creates false refusals.
        return nonNull(value)
                .replaceAll("[\\s\\p{Punct}，。！？、；：‘’“”【】（）《》]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
