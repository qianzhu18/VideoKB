package com.example.server.service;

import com.example.server.dto.KnowledgeAnswer;
import com.example.server.dto.KnowledgeAnswerDraft;
import com.example.server.dto.KnowledgeAskRequest;
import com.example.server.dto.KnowledgeSearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeAnswerServiceTest {

    @Test
    void returnsGeneratedAnswerOnlyWithServerVerifiedCitation() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        KnowledgeAnswerGenerator generator = mock(KnowledgeAnswerGenerator.class);
        KnowledgeSearchHit hit = hit("redis-1", "缓存击穿是热点 key 失效后并发请求同时回源数据库。");
        when(search.searchWithDiagnostics(anyLong(), any())).thenReturn(new KnowledgeSearchService.SearchResult(List.of(hit), List.of()));
        when(generator.generate(eq("什么是缓存击穿"), any())).thenReturn(new KnowledgeAnswerDraft(
                "SUPPORTED", "缓存击穿发生在热点 key 过期时。", List.of(
                new KnowledgeAnswerDraft.CitationDraft("redis-1", "定义缓存击穿", "热点 key 失效后并发请求同时回源数据库"))));

        KnowledgeAnswer answer = new KnowledgeAnswerService(search, generator).ask(
                7L, new KnowledgeAskRequest(3L, null, "什么是缓存击穿", 8, "hybrid"));

        assertEquals(KnowledgeAnswer.SUPPORTED, answer.answerability());
        assertEquals(1, answer.citations().size());
        assertEquals("Redis 系列 - 第 1 讲", answer.citations().getFirst().title());
        assertEquals(60_000L, answer.citations().getFirst().startMs());
    }

    @Test
    void refusesToGenerateWhenRetrievalFindsNoEvidence() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        KnowledgeAnswerGenerator generator = mock(KnowledgeAnswerGenerator.class);
        when(search.searchWithDiagnostics(anyLong(), any())).thenReturn(new KnowledgeSearchService.SearchResult(List.of(), List.of()));

        KnowledgeAnswer answer = new KnowledgeAnswerService(search, generator).ask(
                7L, new KnowledgeAskRequest(3L, null, "不存在的问题", 8, null));

        assertEquals(KnowledgeAnswer.INSUFFICIENT_EVIDENCE, answer.answerability());
        assertTrue(answer.citations().isEmpty());
        verify(generator, never()).generate(any(), any());
    }

    @Test
    void refusesSupportedDraftWhenItsQuoteIsNotInRetrievedSegment() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        KnowledgeAnswerGenerator generator = mock(KnowledgeAnswerGenerator.class);
        when(search.searchWithDiagnostics(anyLong(), any())).thenReturn(new KnowledgeSearchService.SearchResult(List.of(hit("redis-1", "只有这一条原始证据")), List.of()));
        when(generator.generate(any(), any())).thenReturn(new KnowledgeAnswerDraft(
                "SUPPORTED", "这是不应被采纳的结论。", List.of(
                new KnowledgeAnswerDraft.CitationDraft("redis-1", "伪造结论", "模型臆造的引用"))));

        KnowledgeAnswer answer = new KnowledgeAnswerService(search, generator).ask(
                7L, new KnowledgeAskRequest(3L, null, "测试", 8, null));

        assertEquals(KnowledgeAnswer.INSUFFICIENT_EVIDENCE, answer.answerability());
        assertTrue(answer.citations().isEmpty());
    }

    @Test
    void acceptsQuoteThatMatchesCaseFoldedAsrTranscript() {
        // ASR renders English lowercase and space-separated ("g c roots"); the model
        // quotes the canonical spelling ("GC Roots"). Same characters after
        // normalization, so the citation must verify instead of triggering a refusal.
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        KnowledgeAnswerGenerator generator = mock(KnowledgeAnswerGenerator.class);
        when(search.searchWithDiagnostics(anyLong(), any())).thenReturn(new KnowledgeSearchService.SearchResult(List.of(hit("jvm-1", "当一个对象到这个 g c roots 之间没有任何引用相连，就是不可达。")), List.of()));
        when(generator.generate(eq("什么是 GC Roots"), any())).thenReturn(new KnowledgeAnswerDraft(
                "SUPPORTED", "GC Roots 是可达性分析的起点。", List.of(
                new KnowledgeAnswerDraft.CitationDraft("jvm-1", "GC Roots 定义",
                        "到这个 GC Roots 之间没有任何引用相连"))));

        KnowledgeAnswer answer = new KnowledgeAnswerService(search, generator).ask(
                7L, new KnowledgeAskRequest(3L, null, "什么是 GC Roots", 8, "hybrid"));

        assertEquals(KnowledgeAnswer.SUPPORTED, answer.answerability());
        assertEquals(1, answer.citations().size());
    }

    private static KnowledgeSearchHit hit(String segmentId, String transcript) {
        return new KnowledgeSearchHit(segmentId, 9L, "video", 5L, "Redis 系列 - 第 1 讲",
                60_000L, 120_000L, 0.82, transcript, "", "", "hybrid");
    }

    @Test void retrievalWarningsSurviveRefusalAndStreaming() {
        var search=mock(KnowledgeSearchService.class);var generator=mock(KnowledgeAnswerGenerator.class);
        when(search.searchWithDiagnostics(anyLong(),any())).thenReturn(new KnowledgeSearchService.SearchResult(
                List.of(),List.of("BM25 已降级")));
        var service=new KnowledgeAnswerService(search,generator);
        var request=new KnowledgeAskRequest(3L,null,"不存在的问题",8,null);
        assertTrue(service.ask(7L,request).warnings().contains("BM25 已降级"));
        assertTrue(service.askStreaming(7L,request,phase -> {},delta -> {}).warnings().contains("BM25 已降级"));
        verifyNoInteractions(generator);
    }
}
