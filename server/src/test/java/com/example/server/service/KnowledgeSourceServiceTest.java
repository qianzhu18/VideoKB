package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.exception.BusinessException;
import com.example.server.dto.KnowledgeSourceLocationRequest;
import com.example.server.dto.KnowledgeSourceTagsRequest;
import com.example.server.dto.KnowledgeSourceView;
import com.example.server.entity.KnowledgeCollection;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.KnowledgeSourceTag;
import com.example.server.entity.KnowledgeSourceVersion;
import com.example.server.entity.KnowledgeSpace;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.mapper.KnowledgeSourceTagMapper;
import com.example.server.mapper.KnowledgeSourceVersionMapper;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSourceServiceTest {

    @Test
    void createsPendingVersionedSourceForUploadedVideo() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(spaceService.defaultSpaceForUser(7L)).thenReturn(space(3L, 7L));
        when(sourceMapper.insert(any(KnowledgeSource.class))).thenAnswer(invocation -> {
            KnowledgeSource source = invocation.getArgument(0);
            source.setId(21L);
            return 1;
        });
        when(versionMapper.insert(any(KnowledgeSourceVersion.class))).thenAnswer(invocation -> 1);

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        KnowledgeSource source = service.ensureMediaSource(media(9L, 7L, "jvm.mp4", "aabb"));

        assertEquals(21L, source.getId());
        assertEquals(KnowledgeSourceService.SOURCE_TYPE_VIDEO, source.getSourceType());
        assertEquals(3L, source.getSpaceId());
        assertEquals(KnowledgeSourceService.STATUS_PENDING, source.getStatus());
        verify(versionMapper).insert(any(KnowledgeSourceVersion.class));
    }

    @Test
    void movesOwnedSourceIntoTargetCollection() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSource source = new KnowledgeSource();
        source.setId(21L);
        source.setOwnerUserId(7L);
        source.setSpaceId(3L);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        when(sourceMapper.selectById(21L)).thenReturn(source);
        KnowledgeCollection collection = new KnowledgeCollection();
        collection.setId(31L);
        collection.setSpaceId(5L);
        when(collectionService.requireCollectionInSpace(31L, 5L)).thenReturn(collection);

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        KnowledgeSourceView moved = service.move(7L, 21L, new KnowledgeSourceLocationRequest(5L, 31L));

        assertEquals(5L, moved.spaceId());
        assertEquals(31L, moved.collectionId());
        verify(sourceMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void movingToSpaceRootClearsCollectionIdExplicitly() {
        // Regression for the ghost-source bug: updateById skips null fields under the
        // MyBatis-Plus NOT_NULL strategy, leaving a stale collectionId pointing into
        // the previous space. Moving to a root must write collection_id = NULL.
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSource source = new KnowledgeSource();
        source.setId(21L);
        source.setOwnerUserId(7L);
        source.setSpaceId(3L);
        source.setCollectionId(31L);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        when(sourceMapper.selectById(21L)).thenReturn(source);

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        KnowledgeSourceView moved = service.move(7L, 21L, new KnowledgeSourceLocationRequest(5L, null));

        assertEquals(5L, moved.spaceId());
        assertNull(moved.collectionId());
        verify(sourceMapper).update(isNull(), any(UpdateWrapper.class));
        verify(sourceMapper, never()).updateById(any(KnowledgeSource.class));
    }

    @Test
    void filingAPendingSourceAutoStartsDefaultAnalysis() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        AnalysisDispatchService dispatchService = mock(AnalysisDispatchService.class);
        KnowledgeSource source = new KnowledgeSource();
        source.setId(21L);
        source.setOwnerUserId(7L);
        source.setMediaId(66L);
        source.setSpaceId(3L);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        when(sourceMapper.selectById(21L)).thenReturn(source);
        MediaFile media = new MediaFile();
        media.setId(66L);
        media.setUserId(7L);
        when(mediaMapper.selectById(66L)).thenReturn(media);

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, mock(KnowledgeSourceVersionMapper.class),
                mock(KnowledgeSourceTagMapper.class), mediaMapper,
                mock(KnowledgeSpaceService.class), mock(KnowledgeCollectionService.class),
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class), dispatchService,
                mock(com.example.server.service.task.AnalysisTaskService.class));
        service.move(7L, 21L, new KnowledgeSourceLocationRequest(5L, null));

        verify(dispatchService).submitBulk(eq(media), eq(KnowledgeSourceService.DEFAULT_ANALYSIS_GOAL),
                eq(com.example.server.dto.AnalysisMode.GENERAL));
    }

    @Test
    void filingAnAnalyzedSourceDoesNotRedispatch() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        AnalysisDispatchService dispatchService = mock(AnalysisDispatchService.class);
        KnowledgeSource source = new KnowledgeSource();
        source.setId(21L);
        source.setOwnerUserId(7L);
        source.setMediaId(66L);
        source.setSpaceId(3L);
        source.setStatus(KnowledgeSourceService.STATUS_READY);
        when(sourceMapper.selectById(21L)).thenReturn(source);

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, mock(KnowledgeSourceVersionMapper.class),
                mock(KnowledgeSourceTagMapper.class), mock(MediaFileMapper.class),
                mock(KnowledgeSpaceService.class), mock(KnowledgeCollectionService.class),
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class), dispatchService,
                mock(com.example.server.service.task.AnalysisTaskService.class));
        service.move(7L, 21L, new KnowledgeSourceLocationRequest(5L, null));

        verify(dispatchService, never()).submitBulk(any(), org.mockito.ArgumentMatchers.anyString(), any());
        verify(dispatchService, never()).submit(any(), org.mockito.ArgumentMatchers.anyString(), any(), any());
    }

    @Test
    void replaceTagsTrimsDedupesAndSkipsNoopWrites() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSource source = ownedSource(21L, 7L);
        when(sourceMapper.selectById(21L)).thenReturn(source);
        when(tagMapper.selectList(any())).thenReturn(List.of(tagRow(21L, "JVM")));

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        KnowledgeSourceView result = service.replaceTags(
                7L, 21L, new KnowledgeSourceTagsRequest(List.of(" JVM ", "", "JVM")));

        assertEquals(List.of("JVM"), result.tags());
        verify(tagMapper, never()).delete(any());
        verify(tagMapper, never()).insert(any(KnowledgeSourceTag.class));
    }

    @Test
    void replaceTagsRewritesWhenSetChanges() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSource source = ownedSource(21L, 7L);
        when(sourceMapper.selectById(21L)).thenReturn(source);
        when(tagMapper.selectList(any())).thenReturn(List.of(tagRow(21L, "JVM")));

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        KnowledgeSourceView result = service.replaceTags(
                7L, 21L, new KnowledgeSourceTagsRequest(List.of("GC", "面试")));

        assertEquals(List.of("GC", "面试"), result.tags());
        verify(tagMapper).delete(any());
        verify(tagMapper, times(2)).insert(any(KnowledgeSourceTag.class));
    }

    @Test
    void replaceTagsWritesOnlyTheDifferentialWhenCommonTagsRemain() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSource source = ownedSource(21L, 7L);
        when(sourceMapper.selectById(21L)).thenReturn(source);
        when(tagMapper.selectList(any())).thenReturn(List.of(tagRow(21L, "JVM"), tagRow(21L, "面试")));

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        KnowledgeSourceView result = service.replaceTags(
                7L, 21L, new KnowledgeSourceTagsRequest(List.of("面试", "GC")));

        assertEquals(List.of("面试", "GC"), result.tags());
        ArgumentCaptor<KnowledgeSourceTag> inserted = ArgumentCaptor.forClass(KnowledgeSourceTag.class);
        verify(tagMapper, times(1)).insert(inserted.capture());
        assertEquals("GC", inserted.getValue().getTag());
        verify(tagMapper, times(1)).delete(any());
    }

    @Test
    void listWithTagFilterShortCircuitsWhenTagIsUnknown() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        when(tagMapper.selectList(any())).thenReturn(List.of());

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        List<KnowledgeSourceView> result = service.list(7L, 3L, null, "missing-tag");

        assertTrue(result.isEmpty());
        verify(sourceMapper, never()).selectList(any());
    }

    @Test
    void listFillsTagsForReturnedSources() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSourceVersionMapper versionMapper = mock(KnowledgeSourceVersionMapper.class);
        KnowledgeSourceTagMapper tagMapper = mock(KnowledgeSourceTagMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeCollectionService collectionService = mock(KnowledgeCollectionService.class);
        KnowledgeSource source = ownedSource(21L, 7L);
        when(tagMapper.selectList(any()))
                .thenReturn(List.of(tagRow(21L, "面试")))
                .thenReturn(List.of(tagRow(21L, "面试")));
        when(sourceMapper.selectList(any())).thenReturn(List.of(source));

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, versionMapper, tagMapper, mediaMapper, spaceService, collectionService,
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class),
                mock(AnalysisDispatchService.class), mock(com.example.server.service.task.AnalysisTaskService.class));
        List<KnowledgeSourceView> result = service.list(7L, 3L, null, "面试");

        assertEquals(1, result.size());
        assertEquals(List.of("面试"), result.get(0).tags());
    }

    @Test
    void normalizeTagsRejectsOverlongTagAndTooManyTags() {
        assertEquals(List.of("GC"), KnowledgeSourceService.normalizeTags(java.util.Arrays.asList("  ", null, " GC ")));
        assertThrows(BusinessException.class,
                () -> KnowledgeSourceService.normalizeTags(List.of("x".repeat(65))));
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "t" + i)
                .toList();
        assertThrows(BusinessException.class, () -> KnowledgeSourceService.normalizeTags(tooMany));
    }

    private static KnowledgeSource ownedSource(Long id, Long ownerId) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setOwnerUserId(ownerId);
        source.setSpaceId(3L);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        return source;
    }

    private static KnowledgeSourceTag tagRow(Long sourceId, String tag) {
        KnowledgeSourceTag row = new KnowledgeSourceTag();
        row.setSourceId(sourceId);
        row.setTag(tag);
        return row;
    }

    private static KnowledgeSpace space(Long id, Long ownerId) {
        KnowledgeSpace space = new KnowledgeSpace();
        space.setId(id);
        space.setOwnerUserId(ownerId);
        return space;
    }

    private static MediaFile media(Long id, Long ownerId, String name, String hash) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(ownerId);
        media.setFilename(name);
        media.setContentHash(hash);
        return media;
    }

    @Test
    void spaceCatchUpDispatchesOnlyPendingSources() {
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        AnalysisDispatchService dispatchService = mock(AnalysisDispatchService.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        KnowledgeSource pending1 = pendingSource(41L, 71L);
        KnowledgeSource pending2 = pendingSource(42L, 72L);
        when(sourceMapper.selectList(any())).thenReturn(List.of(pending1, pending2));
        when(mediaMapper.selectById(71L)).thenReturn(media(71L));
        // media 72 missing from the table: dispatch skips it instead of failing the batch
        when(mediaMapper.selectById(72L)).thenReturn(null);
        org.mockito.Mockito.doReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED)
                .when(dispatchService).submitBulk(any(MediaFile.class), org.mockito.ArgumentMatchers.anyString(),
                        any(com.example.server.dto.AnalysisMode.class));

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, mock(KnowledgeSourceVersionMapper.class),
                mock(KnowledgeSourceTagMapper.class), mediaMapper,
                spaceService, mock(KnowledgeCollectionService.class),
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class), dispatchService,
                mock(com.example.server.service.task.AnalysisTaskService.class));

        int dispatched = service.dispatchPendingInSpace(7L, 5L);

        assertEquals(1, dispatched);
        verify(dispatchService).submitBulk(any(MediaFile.class),
                eq(KnowledgeSourceService.DEFAULT_ANALYSIS_GOAL), any());
        verify(spaceService).requireOwnedSpace(7L, 5L);
    }

    @Test
    void spaceCatchUpSkipsSourcesThatAlreadyHaveALedgerRow() {
        // 失败过的源保留台账记录作为审计事实：补投递只负责从未投递的源,
        // 失败源走 reindex 从 checkpoint 恢复,不在这里整段重跑烧预算。
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
        AnalysisDispatchService dispatchService = mock(AnalysisDispatchService.class);
        com.example.server.service.task.AnalysisTaskService taskLedger =
                mock(com.example.server.service.task.AnalysisTaskService.class);
        KnowledgeSource neverDispatched = pendingSource(41L, 71L);
        KnowledgeSource failedEarlier = pendingSource(42L, 72L);
        when(sourceMapper.selectList(any())).thenReturn(List.of(neverDispatched, failedEarlier));
        when(mediaMapper.selectById(71L)).thenReturn(media(71L));
        when(taskLedger.latestByMediaIds(any()))
                .thenReturn(java.util.Map.of(72L, new com.example.server.entity.AnalysisTask()));
        org.mockito.Mockito.doReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED)
                .when(dispatchService).submitBulk(any(MediaFile.class), org.mockito.ArgumentMatchers.anyString(),
                        any(com.example.server.dto.AnalysisMode.class));

        KnowledgeSourceService service = new KnowledgeSourceService(
                sourceMapper, mock(KnowledgeSourceVersionMapper.class),
                mock(KnowledgeSourceTagMapper.class), mediaMapper,
                mock(KnowledgeSpaceService.class), mock(KnowledgeCollectionService.class),
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class), dispatchService, taskLedger);

        int dispatched = service.dispatchPendingInSpace(7L, 5L);

        assertEquals(1, dispatched);
        verify(dispatchService, times(1)).submitBulk(any(MediaFile.class),
                eq(KnowledgeSourceService.DEFAULT_ANALYSIS_GOAL), any());
    }

    private KnowledgeSource pendingSource(Long id, Long mediaId) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setOwnerUserId(7L);
        source.setMediaId(mediaId);
        source.setSpaceId(5L);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        return source;
    }

    private MediaFile media(Long id) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(7L);
        return media;
    }
}
