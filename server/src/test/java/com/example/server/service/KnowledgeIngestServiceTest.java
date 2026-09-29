package com.example.server.service;

import com.example.server.exception.BusinessException;
import com.example.server.dto.KnowledgeIngestRequest;
import com.example.server.entity.KnowledgeIngestScan;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.KnowledgeIngestScanMapper;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestServiceTest {

    @TempDir
    Path root;

    @Test
    void ingestIsRejectedWhenNoAllowedRootsConfigured() {
        KnowledgeIngestService service = service("", root);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.ingest(7L, request(root.toString(), true)));
        assertEquals("本地导入未启用：请配置 knowledge.ingest.allowed-roots", error.getMessage());
    }

    @Test
    void ingestIsRejectedOutsideAllowedRoots() {
        KnowledgeIngestService service = service("/data/videos", root);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.ingest(7L, request(root.toString(), true)));
        assertTrue(error.getMessage().startsWith("目录不在授权范围内"));
    }

    @Test
    void dryRunPlansCreatesWithoutTouchingStorage() throws Exception {
        Files.writeString(root.resolve("clip.mp4"), "fake-video-bytes");
        KnowledgeIngestService service = service(root.toString(), root);

        KnowledgeIngestScan scan = service.ingest(7L, request(root.toString(), true));

        assertEquals(1, scan.getCreatedCount());
        assertEquals(Boolean.TRUE, scan.getDryRun());
        verify(minioUtils(service), never()).uploadLocalFile(any(), anyString());
    }

    @Test
    void applyUploadsNewFilesAndDispatchesAnalysis() throws Exception {
        Files.writeString(root.resolve("clip.mp4"), "fake-video-bytes");
        KnowledgeIngestService service = service(root.toString(), root);
        MediaFile media = media(21L, "md5-x");
        when(minioUtils(service).uploadLocalFile(any(), anyString())).thenReturn("minio://x");
        when(mediaService(service).calculateMd5(any(java.io.File.class))).thenReturn("md5-x");
        when(mediaService(service).saveUploadedMedia(eq("clip.mp4"), eq("minio://x"),
                eq(7L), eq("md5-x"))).thenReturn(media);

        KnowledgeIngestScan scan = service.ingest(7L, request(root.toString(), false));

        assertEquals(1, scan.getCreatedCount());
        verify(dispatchService(service)).submitBulk(eq(media), anyString(), any());
        verify(sourceService(service)).registerExternalLocation(7L, 21L,
                root.resolve("clip.mp4").toString());
    }

    @Test
    void applyWithAnalyzeFalseRegistersWithoutDispatchingAnalysis() throws Exception {
        Files.writeString(root.resolve("clip.mp4"), "fake-video-bytes");
        KnowledgeIngestService service = service(root.toString(), root);
        MediaFile media = media(21L, "md5-x");
        when(minioUtils(service).uploadLocalFile(any(), anyString())).thenReturn("minio://x");
        when(mediaService(service).calculateMd5(any(java.io.File.class))).thenReturn("md5-x");
        when(mediaService(service).saveUploadedMedia(eq("clip.mp4"), eq("minio://x"),
                eq(7L), eq("md5-x"))).thenReturn(media);

        KnowledgeIngestScan scan = service.ingest(7L,
                new KnowledgeIngestRequest(root.toString(), 3L, null, false, false));

        assertEquals(1, scan.getCreatedCount());
        verify(dispatchService(service), never()).submit(any(), anyString(), any(), any());
        verify(sourceService(service)).registerExternalLocation(7L, 21L,
                root.resolve("clip.mp4").toString());
    }

    // --- wiring helpers: the service builds collaborators via constructor, so the mocks
    // are captured here to keep verification concise ---

    private MediaService mediaService(KnowledgeIngestService ignored) {
        return lastMediaService;
    }

    private KnowledgeSourceService sourceService(KnowledgeIngestService ignored) {
        return lastSourceService;
    }

    private MinioUtils minioUtils(KnowledgeIngestService ignored) {
        return lastMinioUtils;
    }

    private AnalysisDispatchService dispatchService(KnowledgeIngestService ignored) {
        return lastDispatchService;
    }

    private MediaService lastMediaService;
    private KnowledgeSourceService lastSourceService;
    private MinioUtils lastMinioUtils;
    private AnalysisDispatchService lastDispatchService;

    private KnowledgeIngestService service(String allowedRoots, Path root) {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        MediaService mediaService = mock(MediaService.class);
        AnalysisDispatchService dispatchService = mock(AnalysisDispatchService.class);
        KnowledgeAuditService auditService = mock(KnowledgeAuditService.class);
        KnowledgeIngestScanMapper scanMapper = mock(KnowledgeIngestScanMapper.class);
        MinioUtils minioUtils = mock(MinioUtils.class);

        lastSourceService = sourceService;
        lastMediaService = mediaService;
        lastDispatchService = dispatchService;
        lastMinioUtils = minioUtils;

        return new KnowledgeIngestService(sourceService, mediaService,
                dispatchService, auditService, scanMapper, minioUtils, allowedRoots);
    }

    private static KnowledgeIngestRequest request(String rootPath, boolean dryRun) {
        return new KnowledgeIngestRequest(rootPath, 3L, null, dryRun, null);
    }

    private static MediaFile media(Long id, String hash) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(7L);
        media.setContentHash(hash);
        return media;
    }

    private static KnowledgeSource source(Long id, Long mediaId) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setMediaId(mediaId);
        source.setOwnerUserId(7L);
        source.setSpaceId(3L);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        return source;
    }
}
