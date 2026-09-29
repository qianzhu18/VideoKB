package com.example.server.service;

import com.example.server.dto.TranscriptImportRequest;
import com.example.server.dto.VideoContext;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The zero-reburn import channel must reuse the native indexer end-to-end: context
 * checkpoint first, then indexMedia — never a parallel write path. Ownership and
 * contentless inputs are rejected before anything is written.
 */
class KnowledgeSeedServiceTest {

    private KnowledgeSourceService sourceService;
    private AgentCheckpointService checkpointService;
    private KnowledgeSegmentIndexService indexService;
    private KnowledgeAuditService auditService;
    private KnowledgeSeedService service;

    @BeforeEach
    void setUp() {
        sourceService = mock(KnowledgeSourceService.class);
        checkpointService = mock(AgentCheckpointService.class);
        indexService = mock(KnowledgeSegmentIndexService.class);
        auditService = mock(KnowledgeAuditService.class);
        service = new KnowledgeSeedService(sourceService, checkpointService,
                indexService, auditService);
    }

    @Test
    void importTranscriptWritesCheckpointThenReusesIndexMedia() {
        KnowledgeSource source = source(5L, 21L, 7L);
        when(sourceService.requireSourceByMediaId(21L)).thenReturn(source);
        KnowledgeSegment segment = new KnowledgeSegment();
        when(indexService.indexMedia(21L)).thenReturn(List.of(segment, segment, segment));

        KnowledgeSeedService.ImportResult result = service.importTranscript(7L, request(21L));

        assertEquals(21L, result.mediaId());
        assertEquals(5L, result.sourceId());
        assertEquals(3, result.importedSegments());

        ArgumentCaptor<VideoContext> captor = ArgumentCaptor.forClass(VideoContext.class);
        verify(checkpointService).saveContext(eq(21L), captor.capture());
        VideoContext context = captor.getValue();
        assertEquals(KnowledgeSeedService.IMPORT_SOURCE, context.source());
        assertEquals("migration goal", context.userGoal());
        // The blank window (no text, no OCR) is dropped before the indexer sees it.
        assertEquals(2, context.segments().size());
        assertEquals("三次握手是 TCP 建立连接的过程", context.segments().get(0).transcript());
        assertEquals(List.of("OSI 七层模型"), context.segments().get(1).ocrTexts());

        verify(indexService).indexMedia(21L);
        verify(auditService).record(eq(7L), eq("SOURCE_SEEDED"), eq("SOURCE"), eq(5L),
                eq(3L), eq(9L), anyString());
    }

    @Test
    void importTranscriptRejectsForeignSource() {
        KnowledgeSource source = source(5L, 21L, 8L);
        when(sourceService.requireSourceByMediaId(21L)).thenReturn(source);

        assertThrows(SecurityException.class, () -> service.importTranscript(7L, request(21L)));
        verify(checkpointService, never()).saveContext(anyLong(), any());
        verify(indexService, never()).indexMedia(anyLong());
    }

    @Test
    void importTranscriptRejectsContentlessSegments() {
        KnowledgeSource source = source(5L, 21L, 7L);
        when(sourceService.requireSourceByMediaId(21L)).thenReturn(source);

        TranscriptImportRequest blank = new TranscriptImportRequest(21L, null, List.of(
                new TranscriptImportRequest.TranscriptSegment(0L, 60000L, "  ", List.of()),
                new TranscriptImportRequest.TranscriptSegment(60000L, 120000L, null, null)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.importTranscript(7L, blank));
        assertTrue(error.getMessage().contains("转写分段内容为空"));
        verify(checkpointService, never()).saveContext(anyLong(), any());
    }

    private static TranscriptImportRequest request(Long mediaId) {
        return new TranscriptImportRequest(mediaId, "migration goal", List.of(
                new TranscriptImportRequest.TranscriptSegment(0L, 60000L,
                        "三次握手是 TCP 建立连接的过程", List.of()),
                new TranscriptImportRequest.TranscriptSegment(60000L, 120000L, "",
                        List.of("OSI 七层模型")),
                new TranscriptImportRequest.TranscriptSegment(120000L, 180000L, "  ", List.of())));
    }

    private static KnowledgeSource source(Long id, Long mediaId, Long ownerUserId) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setMediaId(mediaId);
        source.setOwnerUserId(ownerUserId);
        source.setSpaceId(3L);
        source.setCollectionId(9L);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        return source;
    }
}
