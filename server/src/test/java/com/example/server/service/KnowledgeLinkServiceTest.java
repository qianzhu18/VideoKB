package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.KnowledgeLink;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeLinkMapper;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Link lifecycle contract: similarity-gated suggestions, no duplicates, and the
 * SUGGESTED→CONFIRMED/REJECTED transitions guarded by ownership.
 */
class KnowledgeLinkServiceTest {

    @Test
    void suggestKeepsOnlyCrossSourceHitsAboveThresholdAndSkipsKnownPairs() {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        KnowledgeLinkMapper linkMapper = mock(KnowledgeLinkMapper.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        KnowledgeAuditService auditService = mock(KnowledgeAuditService.class);

        KnowledgeSource script = script(21L, 6L);
        when(sourceService.requireOwnedSource(7L, 21L)).thenReturn(script);
        KnowledgeSegment paragraph = new KnowledgeSegment();
        paragraph.setId("para-1");
        paragraph.setTranscript("讲缓存击穿的三种方案");
        when(segmentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(paragraph));
        // 已有一条同段建议，避免重复
        KnowledgeLink known = new KnowledgeLink();
        known.setSourceSegmentId("para-1");
        known.setTargetSegmentId("vid-seg-1");
        when(linkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(known));
        when(embeddingUtils.embed(any(String.class))).thenReturn(List.of(0.1));

        QdrantVectorStore.KnowledgeHit self = hit(21L, "self-seg", 0.9);   // 自身来源必须跳过
        QdrantVectorStore.KnowledgeHit knownPair = hit(9L, "vid-seg-1", 0.8); // 已建议过的配对
        QdrantVectorStore.KnowledgeHit weak = hit(9L, "vid-seg-2", 0.30);  // 低于阈值
        QdrantVectorStore.KnowledgeHit good = hit(9L, "vid-seg-3", 0.62);
        when(vectorStore.searchKnowledge(anyList(), eq(7L), eq(6L), any(), anyInt()))
                .thenReturn(List.of(self, knownPair, weak, good));

        KnowledgeLinkService service = new KnowledgeLinkService(sourceService, sourceMapper,
                segmentMapper, linkMapper, embeddingUtils, vectorStore, auditService, 0.50);
        int created = service.suggest(7L, 21L);

        assertEquals(1, created);
        ArgumentCaptor<KnowledgeLink> saved = ArgumentCaptor.forClass(KnowledgeLink.class);
        verify(linkMapper).insert(saved.capture());
        assertEquals("vid-seg-3", saved.getValue().getTargetSegmentId());
        assertEquals(KnowledgeLinkService.STATUS_SUGGESTED, saved.getValue().getStatus());
        assertEquals(9L, saved.getValue().getTargetSourceId());
        assertTrue(saved.getValue().getConfidence().doubleValue() == 0.62);
    }

    @Test
    void confirmRequiresOwnershipOfTheLinkSource() {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeLinkMapper linkMapper = mock(KnowledgeLinkMapper.class);
        KnowledgeLink link = new KnowledgeLink();
        link.setId(5L);
        link.setSourceId(21L);
        link.setStatus(KnowledgeLinkService.STATUS_SUGGESTED);
        when(linkMapper.selectById(5L)).thenReturn(link);
        when(sourceService.requireOwnedSource(99L, 21L))
                .thenThrow(new SecurityException("无权访问该内容源"));

        KnowledgeLinkService service = new KnowledgeLinkService(sourceService,
                mock(KnowledgeSourceMapper.class), mock(KnowledgeSegmentMapper.class),
                linkMapper, mock(EmbeddingUtils.class), mock(QdrantVectorStore.class),
                mock(KnowledgeAuditService.class), 0.50);

        assertThrows(SecurityException.class, () -> service.confirm(99L, 5L));
        verify(linkMapper, never()).updateById(any(KnowledgeLink.class));

        when(sourceService.requireOwnedSource(7L, 21L)).thenReturn(script(21L, 6L));
        service.confirm(7L, 5L);
        assertEquals(KnowledgeLinkService.STATUS_CONFIRMED, link.getStatus());
        verify(linkMapper).updateById(link);
    }

    @Test
    void suggestRejectsNonScriptSources() {
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeSource video = new KnowledgeSource();
        video.setId(9L);
        video.setSourceType(KnowledgeSourceService.SOURCE_TYPE_VIDEO);
        when(sourceService.requireOwnedSource(7L, 9L)).thenReturn(video);

        KnowledgeLinkService service = new KnowledgeLinkService(sourceService,
                mock(KnowledgeSourceMapper.class), mock(KnowledgeSegmentMapper.class),
                mock(KnowledgeLinkMapper.class), mock(EmbeddingUtils.class),
                mock(QdrantVectorStore.class), mock(KnowledgeAuditService.class), 0.50);

        assertThrows(BusinessException.class, () -> service.suggest(7L, 9L));
    }

    private static KnowledgeSource script(Long id, Long spaceId) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setOwnerUserId(7L);
        source.setSpaceId(spaceId);
        source.setSourceType(KnowledgeSourceService.SOURCE_TYPE_SCRIPT);
        source.setStatus(KnowledgeSourceService.STATUS_READY);
        return source;
    }

    private static QdrantVectorStore.KnowledgeHit hit(Long sourceId, String segmentId, double score) {
        return new QdrantVectorStore.KnowledgeHit(segmentId, sourceId, null, 0, 60000, score);
    }
}
