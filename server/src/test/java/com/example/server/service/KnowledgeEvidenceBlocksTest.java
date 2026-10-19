package com.example.server.service;

import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.dto.*;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeEvidenceBlocksTest {
    private KnowledgeSegment raw(String id, long start, String text) {
        var row=new KnowledgeSegment(); row.setId(id); row.setSourceId(9L); row.setVersionId(11L); row.setMediaId(5L);
        row.setStartMs(start);row.setEndMs(start+60000);row.setTranscript(text);row.setOcrText("");row.setSummary("禁止将派生摘要当原文");return row;
    }
    private KnowledgeRetrievalBlock block(String text,String profile) {
        var row=new KnowledgeRetrievalBlock(); row.setText(text);row.setTextHash(KnowledgeBlockIndexService.hash(text));row.setIndexProfile(profile);return row;
    }
    @Test void sizeBoundariesOverlapButEvidenceTimesRemainExact() {
        var rows=List.of(raw("a",0,"缓存数据库缓存"),raw("b",60000,"缓存数据库缓存"),raw("c",120000,"缓存数据库缓存"),raw("d",180000,"缓存数据库缓存"));
        var chunks=new EvidenceBlockChunker().split(rows);
        assertEquals(List.of("a","b","c"),chunks.getFirst().evidence().stream().map(KnowledgeSegment::getId).toList());
        assertEquals(List.of("c","d"),chunks.getLast().evidence().stream().map(KnowledgeSegment::getId).toList());
        assertTrue(chunks.stream().noneMatch(b -> b.text().contains("派生摘要")));
        assertEquals(120000,chunks.getLast().evidence().getFirst().getStartMs());
    }
    @Test void topicTransitionsAndTimeGapsDoNotBridgeUnrelatedEvidence() {
        var chunks=new EvidenceBlockChunker().split(List.of(raw("a",0,"缓存击穿数据库互斥锁"),raw("b",60000,"下面我们讨论缓存击穿数据库"),raw("c",600000,"缓存击穿数据库")));
        assertEquals(3,chunks.size());assertTrue(chunks.stream().allMatch(c -> c.evidence().size()==1));
    }
    @Test void oversizedWindowUsesSentenceBoundariesWithoutInventingPreciseTime() {
        String text="知识🙂".repeat(900)+"。结束。";
        var original=raw("long",120000,text);var chunks=new EvidenceBlockChunker().split(List.of(original));
        assertEquals(text,String.join("",chunks.stream().map(EvidenceBlockChunker.Block::text).toList()));
        assertTrue(chunks.stream().allMatch(c -> c.text().codePointCount(0,c.text().length())<=1400));
        assertTrue(chunks.stream().allMatch(c -> c.evidence().equals(List.of(original))));
    }
    @Test void identicalRebuildReusesVectorsButOwnerModelOrProfileDoesNot() {
        var cache=mock(KnowledgeEmbeddingCacheMapper.class);var embeddings=mock(EmbeddingUtils.class);
        var persisted=new HashMap<String,KnowledgeEmbeddingCache>();
        when(cache.selectBatchIds(any())).thenAnswer(call -> ((Collection<String>)call.getArgument(0)).stream().map(persisted::get).filter(Objects::nonNull).toList());
        when(cache.insert(any(KnowledgeEmbeddingCache.class))).thenAnswer(call -> {var row=(KnowledgeEmbeddingCache)call.getArgument(0);persisted.put(row.getCacheKey(),row);return 1;});
        when(embeddings.embedBatch(anyList())).thenAnswer(call -> ((List<String>)call.getArgument(0)).stream().map(t -> List.of(1.0,0.0)).toList());
        var service=new KnowledgeBlockIndexService(mock(KnowledgeRetrievalBlockMapper.class),cache,mock(KnowledgeSourceVersionMapper.class),embeddings,mock(QdrantVectorStore.class),new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var rows=List.of(block("原始知识",EvidenceBlockChunker.PROFILE),block("原始知识",EvidenceBlockChunker.PROFILE));
        assertEquals(service.cachedVectors(7L,"modelA",rows),service.cachedVectors(7L,"modelA",rows));
        verify(embeddings,times(1)).embedBatch(anyList());
        service.cachedVectors(8L,"modelA",rows);service.cachedVectors(7L,"modelB",rows);
        service.cachedVectors(7L,"modelA",List.of(block("原始知识","new-profile")));
        verify(embeddings,times(4)).embedBatch(anyList());
    }
    @Test void malformedVectorCannotBePublishedAndOriginalRowsAreNeverDeleted() {
        var cache=mock(KnowledgeEmbeddingCacheMapper.class);var embeddings=mock(EmbeddingUtils.class);var vectors=mock(QdrantVectorStore.class);
        when(cache.selectBatchIds(any())).thenReturn(List.of());when(embeddings.embedBatch(anyList())).thenReturn(List.of(List.of(Double.NaN)));
        var service=new KnowledgeBlockIndexService(mock(KnowledgeRetrievalBlockMapper.class),cache,mock(KnowledgeSourceVersionMapper.class),embeddings,vectors,new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var source=new KnowledgeSource();source.setId(9L);source.setOwnerUserId(7L);source.setMediaId(5L);
        var version=new KnowledgeSourceVersion();version.setId(11L);version.setVersionNo(1);version.setEmbeddingModel("modelA");
        assertThrows(IllegalStateException.class,()->service.index(source,version,List.of(raw("one",0,"原始证据"))));
        verifyNoInteractions(vectors);verify(cache,never()).insert(any(KnowledgeEmbeddingCache.class));
    }
    @Test void citationMustNameOriginalWindowAndSummaryOnlyQuoteIsRejected() {
        var search=mock(KnowledgeSearchService.class);var generator=mock(KnowledgeAnswerGenerator.class);
        var raw=KnowledgeEvidence.from(raw("original",60000,"缓存击穿使用互斥锁保护数据库"));
        var hit=new KnowledgeSearchHit("block",9L,"VIDEO",5L,"课程",0,180000,0.9,"检索块", "", "摘要说缓存永不过期", "vector",11L,EvidenceBlockChunker.PROFILE,List.of(raw));
        when(search.searchWithDiagnostics(anyLong(),any())).thenReturn(new KnowledgeSearchService.SearchResult(List.of(hit), List.of()));
        var service=new KnowledgeAnswerService(search,generator);var request=new KnowledgeAskRequest(3L,null,"缓存",8,"vector");
        when(generator.generate(anyString(),anyList())).thenReturn(new KnowledgeAnswerDraft("SUPPORTED","使用互斥锁",List.of(new KnowledgeAnswerDraft.CitationDraft("original","使用互斥锁","互斥锁保护数据库"))));
        var answer=service.ask(7L,request);assertEquals(60000,answer.citations().getFirst().startMs());assertEquals(11L,answer.citations().getFirst().versionId());
        when(generator.generate(anyString(),anyList())).thenReturn(new KnowledgeAnswerDraft("SUPPORTED","永不过期",List.of(new KnowledgeAnswerDraft.CitationDraft("block","永不过期","缓存永不过期"))));
        assertEquals("INSUFFICIENT_EVIDENCE",service.ask(7L,request).answerability());
    }
}
