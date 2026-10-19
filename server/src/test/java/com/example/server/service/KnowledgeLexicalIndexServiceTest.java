package com.example.server.service;

import com.example.server.entity.*;
import com.example.server.mapper.*;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeLexicalIndexServiceTest {
    private final MilvusLexicalClient client=mock(MilvusLexicalClient.class);
    private final KnowledgeLexicalGenerationMapper receipts=mock(KnowledgeLexicalGenerationMapper.class);
    private final KnowledgeBlockIndexService blocks=mock(KnowledgeBlockIndexService.class);
    private final KnowledgeSegmentMapper segments=mock(KnowledgeSegmentMapper.class);
    private final KnowledgeLexicalIndexService service=new KnowledgeLexicalIndexService(client,receipts,blocks,segments,
            mock(KnowledgeSourceVersionMapper.class),mock(KnowledgeSourceService.class),mock(RedissonClient.class));
    private final KnowledgeQueryScope scope=new KnowledgeQueryScope(7L,Map.of(9L,new KnowledgeQueryScope.Generation(11L,1)));
    private KnowledgeLexicalGeneration receipt() {
        var row=new KnowledgeLexicalGeneration(); row.setVersionId(11L);row.setSourceId(9L);row.setOwnerUserId(7L);row.setDocumentCount(2);return row;
    }
    @Test void incompleteBackfillMustNotReturnPartialBm25Results() {
        when(client.backendKey()).thenReturn("backend");when(receipts.find(anyString(),anyList())).thenReturn(List.of());
        assertThrows(IllegalStateException.class,()->service.search("缓存",scope,5));verify(client,never()).search(any(),any(),anyInt());
    }
    @Test void lostMilvusDataRequiresBackfillEvenWhenSqlReceiptExists() {
        when(client.backendKey()).thenReturn("backend");when(receipts.find(anyString(),anyList())).thenReturn(List.of(receipt()));
        when(client.count(scope)).thenReturn(1L);
        assertThrows(IllegalStateException.class,()->service.search("缓存",scope,5));verify(client,never()).search(any(),any(),anyInt());
    }
    @Test void receiptFromAnotherOwnerCannotAuthorizeRecall() {
        var row=receipt();row.setOwnerUserId(8L);when(client.backendKey()).thenReturn("backend");
        when(receipts.find(anyString(),anyList())).thenReturn(List.of(row));
        assertThrows(IllegalStateException.class,()->service.search("缓存",scope,5));verify(client,never()).count(any());
    }
    @Test void completeGenerationCanReturnValidEmptyLexicalResultsWithoutFallback() {
        when(client.backendKey()).thenReturn("backend");when(receipts.find(anyString(),anyList())).thenReturn(List.of(receipt()));
        when(client.count(scope)).thenReturn(2L);when(client.search("无关",scope,5)).thenReturn(List.of());
        assertEquals(List.of(),service.search("无关",scope,5));verify(client).search("无关",scope,5);
    }
    @Test void writeFailureCannotProduceCompletionReceiptAndSummaryIsNotIndexed() {
        var source=new KnowledgeSource();source.setId(9L);source.setOwnerUserId(7L);
        var version=new KnowledgeSourceVersion();version.setId(11L);version.setVersionNo(1);
        var raw=new KnowledgeSegment();raw.setId("one");raw.setSourceId(9L);raw.setVersionId(11L);
        raw.setTranscript("原始证据");raw.setOcrText("原始画面");raw.setSummary("禁止索引摘要");
        when(client.enabled()).thenReturn(true);when(blocks.inScope(any())).thenReturn(List.of(raw));
        doThrow(new IllegalStateException("write failed")).when(client).upsert(anyList());
        assertThrows(IllegalStateException.class,()->service.index(source,version));
        verify(client).upsert(argThat(rows -> rows.size()==1 && !rows.getFirst().text().contains("摘要")));
        verify(receipts,never()).complete(any(),any(),any(),any(),anyInt());
    }
}
