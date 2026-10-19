package com.example.server.service;

import com.example.server.dto.KnowledgeSearchHit;
import com.example.server.dto.KnowledgeSearchRequest;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTest {

    @Test
    void searchWithVectorStrategyRecallsFromVectorStoreOnly() {
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeVectorIndex vectorStore = mock(KnowledgeVectorIndex.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        when(embeddingUtils.embed(any(String.class))).thenReturn(List.of(0.1, 0.2));
        when(vectorStore.search(any(), any(), anyInt()))
                .thenReturn(List.of(new KnowledgeVectorIndex.Hit("seg-1", 9L, 0.91)));
        when(segmentMapper.selectBatchIds(any())).thenReturn(List.of(segment("seg-1")));
        when(sourceMapper.selectById(9L)).thenReturn(source(9L, "课程回放.mp4"));

        KnowledgeSearchService service = new KnowledgeSearchService(
                spaceService, collectionService, segmentMapper, sourceMapper, vectorStore,
                embeddingUtils, 0.5, scope(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), Runnable::run);
        List<KnowledgeSearchHit> hits = service.search(
                7L, new KnowledgeSearchRequest(3L, null, "缓存击穿", 5, "vector"));

        assertEquals(1, hits.size());
        KnowledgeSearchHit hit = hits.get(0);
        assertEquals("课程回放.mp4", hit.title());
        assertEquals(9L, hit.sourceId());
        assertEquals(0L, hit.startMs());
        assertEquals("vector", hit.matchType());
        assertEquals("第一句", hit.transcript());
        verify(segmentMapper, never()).selectList(any());
    }

    @Test
    void searchWithHybridStrategyKeepsWorkingWhenVectorStoreFails() {
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeVectorIndex vectorStore = mock(KnowledgeVectorIndex.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        when(embeddingUtils.embed(any(String.class))).thenReturn(List.of(0.1, 0.2));
        when(vectorStore.search(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("Qdrant down"));
        when(sourceMapper.selectList(any())).thenReturn(List.of(source(9L, "课程回放.mp4")));
        when(sourceMapper.selectById(9L)).thenReturn(source(9L, "课程回放.mp4"));
        when(segmentMapper.selectList(any())).thenReturn(List.of(segment("seg-2")));

        KnowledgeSearchService service = new KnowledgeSearchService(
                spaceService, collectionService, segmentMapper, sourceMapper, vectorStore,
                embeddingUtils, 0.5, scope(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), Runnable::run);
        List<KnowledgeSearchHit> hits = service.search(
                7L, new KnowledgeSearchRequest(3L, null, "缓存击穿怎么处理", 5, "hybrid"));

        assertEquals(1, hits.size());
        assertEquals("hybrid", hits.get(0).matchType());
        assertEquals("seg-2", hits.get(0).segmentId());
    }

    @Test
    void hybridFusionRanksDualChannelHitsFirst() {
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeVectorIndex vectorStore = mock(KnowledgeVectorIndex.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        when(embeddingUtils.embed(any(String.class))).thenReturn(List.of(0.1, 0.2));
        when(vectorStore.search(any(), any(), anyInt()))
                .thenReturn(List.of(
                        new KnowledgeVectorIndex.Hit("seg-vec", 9L, 0.9),
                        new KnowledgeVectorIndex.Hit("seg-both", 9L, 0.8)));
        when(sourceMapper.selectList(any())).thenReturn(List.of(source(9L, "课程回放.mp4")));
        when(sourceMapper.selectById(9L)).thenReturn(source(9L, "课程回放.mp4"));
        when(segmentMapper.selectList(any())).thenReturn(List.of(segment("seg-both"), segment("seg-kw")));
        when(segmentMapper.selectBatchIds(any())).thenReturn(List.of(
                segment("seg-vec"), segment("seg-both"), segment("seg-kw")));

        KnowledgeSearchService service = new KnowledgeSearchService(
                spaceService, collectionService, segmentMapper, sourceMapper, vectorStore,
                embeddingUtils, 0.5, scope(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), Runnable::run);
        List<KnowledgeSearchHit> hits = service.search(
                7L, new KnowledgeSearchRequest(3L, null, "缓存击穿", 5, "hybrid"));

        assertEquals(3, hits.size());
        assertEquals("seg-both", hits.get(0).segmentId());
    }

    @Test
    void searchReturnsEmptyWhenNoEvidenceFound() {
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeVectorIndex vectorStore = mock(KnowledgeVectorIndex.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        when(embeddingUtils.embed(any(String.class))).thenReturn(List.of(0.1, 0.2));
        when(vectorStore.search(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(sourceMapper.selectList(any())).thenReturn(List.of(source(9L, "课程回放.mp4")));
        when(segmentMapper.selectList(any())).thenReturn(List.of());

        KnowledgeSearchService service = new KnowledgeSearchService(
                spaceService, collectionService, segmentMapper, sourceMapper, vectorStore,
                embeddingUtils, 0.5, scope(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), Runnable::run);
        List<KnowledgeSearchHit> hits = service.search(
                7L, new KnowledgeSearchRequest(3L, null, "完全无关的问题", 5, "hybrid"));

        assertTrue(hits.isEmpty());
    }

    @Test
    void vectorStrategyDropsHitsBelowRelevanceThreshold() {
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeVectorIndex vectorStore = mock(KnowledgeVectorIndex.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        when(embeddingUtils.embed(any(String.class))).thenReturn(List.of(0.1, 0.2));
        when(vectorStore.search(any(), any(), anyInt()))
                .thenReturn(List.of(
                        new KnowledgeVectorIndex.Hit("seg-strong", 9L, 0.61),
                        new KnowledgeVectorIndex.Hit("seg-weak", 9L, 0.37)));
        when(segmentMapper.selectBatchIds(any())).thenReturn(List.of(
                segment("seg-strong"), segment("seg-weak")));
        when(sourceMapper.selectById(9L)).thenReturn(source(9L, "课程回放.mp4"));

        KnowledgeSearchService service = new KnowledgeSearchService(
                spaceService, collectionService, segmentMapper, sourceMapper, vectorStore,
                embeddingUtils, 0.5, scope(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), Runnable::run);
        List<KnowledgeSearchHit> hits = service.search(
                7L, new KnowledgeSearchRequest(3L, null, "缓存击穿", 5, "vector"));

        assertEquals(1, hits.size());
        assertEquals("seg-strong", hits.get(0).segmentId());
        assertTrue(hits.get(0).score() >= 0.5);
    }

    @Test
    void removalDuringRecallCannotLeakEvidenceIntoOldFolder() {
        var resolver = scope();
        when(resolver.resolve(any(), any(), any())).thenReturn(
                new KnowledgeQueryScope(7L, java.util.Map.of(9L, new KnowledgeQueryScope.Generation(11L, 1))),
                new KnowledgeQueryScope(7L, java.util.Map.of()));
        var segments = mock(KnowledgeSegmentMapper.class); var sources = mock(KnowledgeSourceMapper.class);
        when(segments.selectList(any())).thenReturn(List.of(segment("old")));
        when(sources.selectById(9L)).thenReturn(source(9L, "course"));
        var service = new KnowledgeSearchService(mock(KnowledgeSpaceService.class), mock(KnowledgeCollectionService.class),
                segments, sources, mock(KnowledgeVectorIndex.class), mock(EmbeddingUtils.class), 0.5, resolver,
                new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), Runnable::run);
        assertTrue(service.search(7L, new KnowledgeSearchRequest(3L, 4L, "缓存", 5, "keyword")).isEmpty());
    }

    @Test
    void publicationDuringRecallKeepsQuerySnapshotReadable() {
        var resolver = scope();
        when(resolver.resolve(any(), any(), any())).thenReturn(
                new KnowledgeQueryScope(7L, java.util.Map.of(9L, new KnowledgeQueryScope.Generation(11L, 1))),
                new KnowledgeQueryScope(7L, java.util.Map.of(9L, new KnowledgeQueryScope.Generation(12L, 2))));
        var segments = mock(KnowledgeSegmentMapper.class); var sources = mock(KnowledgeSourceMapper.class);
        when(segments.selectList(any())).thenReturn(List.of(segment("old")));
        when(sources.selectById(9L)).thenReturn(source(9L, "course"));
        var service = new KnowledgeSearchService(mock(KnowledgeSpaceService.class), mock(KnowledgeCollectionService.class),
                segments, sources, mock(KnowledgeVectorIndex.class), mock(EmbeddingUtils.class), 0.5, resolver,
                new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), Runnable::run);
        assertEquals(1, service.search(7L, new KnowledgeSearchRequest(3L, 4L, "缓存", 5, "keyword")).size());
    }

    private static KnowledgeScopeResolver scope() {
        var resolver = mock(KnowledgeScopeResolver.class);
        when(resolver.resolve(any(), any(), any())).thenReturn(new KnowledgeQueryScope(7L,
                java.util.Map.of(9L, new KnowledgeQueryScope.Generation(11L, 1))));
        return resolver;
    }

    private static KnowledgeSegment segment(String id) {
        KnowledgeSegment segment = new KnowledgeSegment();
        segment.setId(id);
        segment.setSourceId(9L);
        segment.setVersionId(11L);
        segment.setMediaId(5L);
        segment.setStartMs(0L);
        segment.setEndMs(60_000L);
        segment.setTranscript("第一句");
        segment.setOcrText("画面字");
        segment.setSummary("摘要");
        return segment;
    }

    private static KnowledgeSource source(Long id, String title) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setTitle(title);
        source.setOwnerUserId(7L);
        source.setSpaceId(3L);
        source.setStatus(KnowledgeSourceService.STATUS_READY);
        return source;
    }
}
