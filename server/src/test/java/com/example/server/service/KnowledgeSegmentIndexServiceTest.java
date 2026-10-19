package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.KnowledgeSourceVersion;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceVersionMapper;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSegmentIndexServiceTest {

    @Test
    void indexMediaPersistsSegmentsMarksVersionReadyAndUpsertsVectors() {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
        VideoChunkingService chunkingService = mock(VideoChunkingService.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        when(sourceService.requireSourceByMediaId(5L)).thenReturn(source(9L, 5L));
        when(versionMapper.selectOne(any())).thenReturn(version(11L, 1));
        when(versionMapper.insert(any(KnowledgeSourceVersion.class))).thenAnswer(call -> {
            ((KnowledgeSourceVersion) call.getArgument(0)).setId(12L); return 1;
        });
        when(checkpointService.loadChunks(5L)).thenReturn(List.of(chunk(0, 60_000, "0.1", "0.2")));
        when(embeddingUtils.embedBatch(any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> List.of(0.5)).toList();
        });

        KnowledgeSegmentIndexService service = new KnowledgeSegmentIndexService(
                sourceService, versionMapper, segmentMapper, vectorStore, checkpointService,
                chunkingService, embeddingUtils, mock(KnowledgeAuditService.class), "BAAI/bge-m3", mock(KnowledgeIndexPublisher.class), locks(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(KnowledgeBlockIndexService.class), "legacy-v1");
        List<KnowledgeSegment> segments = service.indexMedia(5L);

        assertEquals(2, segments.size());
        verify(segmentMapper, never()).delete(any());
        verify(segmentMapper, times(2)).insert(any(KnowledgeSegment.class));
        ArgumentCaptor<List<QdrantVectorStore.KnowledgePoint>> points = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, never()).deleteSource(9L);
        verify(vectorStore).upsertKnowledge(points.capture());
        assertEquals(2, points.getValue().size());
        assertEquals("9", points.getValue().get(0).payload().getString("sourceId"));
        assertEquals("3", points.getValue().get(0).payload().getString("spaceId"));
        verify(versionMapper).insert(any(KnowledgeSourceVersion.class));
        verify(sourceService, never()).updateIndexStatus(any(), any());
    }

    @Test
    void indexMediaMarksVersionFailedWhenNoSegmentsAvailable() {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
        VideoChunkingService chunkingService = mock(VideoChunkingService.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        KnowledgeSource source = source(9L, 5L);
        when(sourceService.requireSourceByMediaId(5L)).thenReturn(source);
        when(versionMapper.selectOne(any())).thenReturn(version(11L, 1));
        when(versionMapper.insert(any(KnowledgeSourceVersion.class))).thenAnswer(call -> {
            ((KnowledgeSourceVersion) call.getArgument(0)).setId(12L); return 1;
        });
        when(checkpointService.loadChunks(5L)).thenReturn(List.of());
        when(checkpointService.loadContext(5L)).thenReturn(
                new VideoContext("minio://video", "goal", List.of()));
        when(chunkingService.build(any())).thenReturn(List.of());

        KnowledgeSegmentIndexService service = new KnowledgeSegmentIndexService(
                sourceService, versionMapper, segmentMapper, vectorStore, checkpointService,
                chunkingService, embeddingUtils, mock(KnowledgeAuditService.class), "BAAI/bge-m3", mock(KnowledgeIndexPublisher.class), locks(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(KnowledgeBlockIndexService.class), "legacy-v1");
        assertThrows(IllegalStateException.class, () -> service.indexMedia(5L));

        verify(versionMapper).insert(any(KnowledgeSourceVersion.class));
        verify(sourceService, never()).updateIndexStatus(any(), any());
        verify(vectorStore, never()).upsertKnowledge(any());
    }

    @Test
    void indexMediaEmbedsEachSegmentForSegmentLevelEvidence() {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
        VideoChunkingService chunkingService = mock(VideoChunkingService.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        when(sourceService.requireSourceByMediaId(5L)).thenReturn(source(9L, 5L));
        when(versionMapper.selectOne(any())).thenReturn(version(11L, 1));
        when(versionMapper.insert(any(KnowledgeSourceVersion.class))).thenAnswer(call -> {
            ((KnowledgeSourceVersion) call.getArgument(0)).setId(12L); return 1;
        });
        when(checkpointService.loadChunks(5L)).thenReturn(List.of(chunk(0, 60_000, "0.1", "0.2")));
        when(embeddingUtils.embedBatch(any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> List.of(0.5)).toList();
        });

        KnowledgeSegmentIndexService service = new KnowledgeSegmentIndexService(
                sourceService, versionMapper, segmentMapper, vectorStore, checkpointService,
                chunkingService, embeddingUtils, mock(KnowledgeAuditService.class), "BAAI/bge-m3", mock(KnowledgeIndexPublisher.class), locks(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(KnowledgeBlockIndexService.class), "legacy-v1");
        service.indexMedia(5L);

        // Evidence granularity is the segment, so every segment gets its own vector even
        // when the checkpoint chunk already carries a coarser chunk-level embedding.
        ArgumentCaptor<List<String>> embeddingBatch = ArgumentCaptor.forClass(List.class);
        verify(embeddingUtils).embedBatch(embeddingBatch.capture());
        assertEquals(2, embeddingBatch.getValue().size());
    }

    @Test
    void listSegmentsRejectsForeignOwnerAndReturnsRowsForTheOwner() {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
        VideoChunkingService chunkingService = mock(VideoChunkingService.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        // source belongs to user 7 and only the published version is visible.
        var ready = source(9L, 5L); ready.setStatus("READY");
        when(sourceService.requireSourceByMediaId(5L)).thenReturn(ready);
        var published = version(11L, 1); published.setStatus("READY");
        when(versionMapper.selectOne(any())).thenReturn(published);
        KnowledgeSegment row = new KnowledgeSegment();
        row.setId("seg-1");
        row.setMediaId(5L);
        when(segmentMapper.selectList(any())).thenReturn(List.of(row));

        KnowledgeSegmentIndexService service = new KnowledgeSegmentIndexService(
                sourceService, versionMapper, segmentMapper, vectorStore, checkpointService,
                chunkingService, embeddingUtils, mock(KnowledgeAuditService.class), "BAAI/bge-m3", mock(KnowledgeIndexPublisher.class), locks(), new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(KnowledgeBlockIndexService.class), "legacy-v1");

        assertThrows(SecurityException.class, () -> service.listSegments(99L, 5L));
        assertEquals(List.of(row), service.listSegments(7L, 5L));
    }

    private static org.redisson.api.RedissonClient locks() {
        var client = mock(org.redisson.api.RedissonClient.class);
        var lock = mock(org.redisson.api.RLock.class);
        when(client.getLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(lock);
        try { when(lock.tryLock(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true); }
        catch (InterruptedException e) { throw new AssertionError(e); }
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return client;
    }

    @Test void enabledBm25FailureCannotPublishNewGenerationOrDeleteOldDenseIndex() {
        var sources=mock(KnowledgeSourceService.class);var versions=mock(KnowledgeSourceVersionMapper.class);
        var segments=mock(KnowledgeSegmentMapper.class);var vectors=mock(QdrantVectorStore.class);
        var checkpoints=mock(AgentCheckpointService.class);var embeddings=mock(EmbeddingUtils.class);
        var publisher=mock(KnowledgeIndexPublisher.class);var lexical=mock(KnowledgeLexicalIndexService.class);
        var current=source(9L,5L);current.setStatus("READY");
        when(sources.requireSourceByMediaId(5L)).thenReturn(current);
        when(versions.selectOne(any())).thenReturn(version(11L,1));
        when(versions.insert(any(KnowledgeSourceVersion.class))).thenAnswer(call -> { ((KnowledgeSourceVersion)call.getArgument(0)).setId(12L);return 1; });
        when(checkpoints.loadChunks(5L)).thenReturn(List.of(chunk(0,60000,"1")));
        when(embeddings.embedBatch(any())).thenReturn(List.of(List.of(1.0),List.of(1.0)));
        when(lexical.enabled()).thenReturn(true);
        when(lexical.index(any(),any())).thenThrow(new IllegalStateException("BM25 write failed"));
        var service=new KnowledgeSegmentIndexService(sources,versions,segments,vectors,checkpoints,
                mock(VideoChunkingService.class),embeddings,mock(KnowledgeAuditService.class),"model",publisher,
                locks(),new KnowledgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),mock(KnowledgeBlockIndexService.class),"legacy-v1");
        org.springframework.test.util.ReflectionTestUtils.setField(service,"lexicalIndex",lexical);
        assertThrows(IllegalStateException.class,()->service.indexMedia(5L));
        verify(publisher,never()).publish(any(),any());verify(publisher).fail(eq(current),any(),eq("BM25 write failed"));
        verify(vectors,never()).deleteSource(any());assertEquals(1,current.getCurrentVersion());
    }

    private static KnowledgeSource source(Long id, Long mediaId) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setMediaId(mediaId);
        source.setOwnerUserId(7L);
        source.setSpaceId(3L);
        source.setCollectionId(null);
        source.setContentHash("aabb");
        source.setCurrentVersion(1);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        return source;
    }

    private static KnowledgeSourceVersion version(Long id, int versionNo) {
        KnowledgeSourceVersion version = new KnowledgeSourceVersion();
        version.setId(id);
        version.setSourceId(9L);
        version.setVersionNo(versionNo);
        version.setStatus(KnowledgeSourceService.STATUS_PENDING);
        return version;
    }

    private static VideoChunk chunk(long startMs, long endMs, String... embeddingValues) {
        List<Double> embedding = java.util.Arrays.stream(embeddingValues).map(Double::parseDouble).toList();
        return new VideoChunk(startMs, endMs, "摘要", List.of("关键词"),
                List.of(new VideoContext.VideoSegment(startMs, startMs + 30_000, "第一句", List.of(), List.of()),
                        new VideoContext.VideoSegment(startMs + 30_000, endMs, "第二句", List.of("画面字"), List.of())),
                embedding);
    }
}
