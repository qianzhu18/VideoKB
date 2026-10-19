package com.example.server.service;

import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.dto.*;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeBm25SearchTest {
    private final KnowledgeSegmentMapper segments=mock(KnowledgeSegmentMapper.class);
    private final KnowledgeSourceMapper sources=mock(KnowledgeSourceMapper.class);
    private final KnowledgeLexicalIndexService lexical=mock(KnowledgeLexicalIndexService.class);
    private final KnowledgeScopeResolver scopes=mock(KnowledgeScopeResolver.class);
    private final KnowledgeQueryScope scope=new KnowledgeQueryScope(7L,Map.of(9L,new KnowledgeQueryScope.Generation(11L,1)));
    private KnowledgeSearchService service() {
        var source=new KnowledgeSource();source.setId(9L);source.setOwnerUserId(7L);source.setStatus("READY");
        when(sources.selectById(9L)).thenReturn(source);
        when(scopes.resolve(any(),any(),any())).thenReturn(scope);
        when(lexical.enabled()).thenReturn(true);
        var service=new KnowledgeSearchService(mock(KnowledgeSpaceService.class),mock(KnowledgeCollectionService.class),
                segments,sources,mock(KnowledgeVectorIndex.class),mock(EmbeddingUtils.class),0.45,scopes,
                new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),Runnable::run);
        ReflectionTestUtils.setField(service,"lexicalIndex",lexical);
        return service;
    }
    private KnowledgeSegment row(String id,Long version) {
        var row=new KnowledgeSegment();row.setId(id);row.setSourceId(9L);row.setVersionId(version);row.setTranscript("缓存互斥锁");row.setMediaId(5L);row.setStartMs(0L);row.setEndMs(60000L);return row;
    }
    private KnowledgeSearchRequest request(String strategy) { return new KnowledgeSearchRequest(3L,4L,"缓存",8,strategy); }

    @Test void successfulBm25BackfillsExactVersionAndNeverCallsLike() {
        var service=service();when(lexical.search(any(),any(),anyInt())).thenReturn(List.of(new MilvusLexicalClient.Hit("valid",9L,11L,2.7)));
        when(segments.selectBatchIds(any())).thenReturn(List.of(row("valid",11L)));
        var result=service.searchWithDiagnostics(7L,request("keyword"));
        assertEquals("bm25",result.hits().getFirst().matchType());assertTrue(result.warnings().isEmpty());
        verify(segments,never()).selectList(any());
    }
    @Test void malformedStaleHitCannotBeSubstitutedWithCurrentEvidence() {
        var service=service();when(lexical.search(any(),any(),anyInt())).thenReturn(List.of(new MilvusLexicalClient.Hit("stale",9L,10L,2.7)));
        when(segments.selectBatchIds(any())).thenReturn(List.of(row("stale",11L)));
        assertTrue(service.search(7L,request("keyword")).isEmpty());
    }
    @Test void removalDuringBm25RecallRevokesOldFolderEvidence() {
        var service=service();when(scopes.resolve(any(),any(),any())).thenReturn(scope,new KnowledgeQueryScope(7L,Map.of()));
        when(lexical.search(any(),any(),anyInt())).thenReturn(List.of(new MilvusLexicalClient.Hit("valid",9L,11L,2.7)));
        when(segments.selectBatchIds(any())).thenReturn(List.of(row("valid",11L)));
        assertTrue(service.search(7L,request("keyword")).isEmpty());
    }
    @Test void bm25FailureFallsBackWithRequestLocalWarningsForBothKeywordAndHybrid() {
        var service=service();when(lexical.search(any(),any(),anyInt())).thenThrow(new IllegalStateException("unavailable"));
        when(segments.selectList(any())).thenReturn(List.of(row("fallback",11L)));
        for (String strategy:List.of("keyword","hybrid")) {
            var result=service.searchWithDiagnostics(7L,request(strategy));
            assertEquals(1,result.hits().size());assertEquals(1,result.warnings().size());assertTrue(result.warnings().getFirst().contains("降级"));
        }
        doReturn(List.of()).when(lexical).search(any(),any(),anyInt());
        var recovered=service.searchWithDiagnostics(7L,request("keyword"));
        assertTrue(recovered.hits().isEmpty());assertTrue(recovered.warnings().isEmpty());
    }
    @Test void explicitLikeRemainsAComparisonPathWithoutInvokingMilvus() {
        var service=service();when(segments.selectList(any())).thenReturn(List.of(row("like",11L)));
        assertEquals("keyword",service.search(7L,request("like")).getFirst().matchType());
        verify(lexical,never()).search(any(),any(),anyInt());
    }
}
