package com.example.server.service;

import com.example.server.dto.KnowledgeSourceLocationRequest;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgePlacementServiceTest {
    private final KnowledgePlacementMapper rows = mock(KnowledgePlacementMapper.class);
    private final KnowledgeSourceMapper sources = mock(KnowledgeSourceMapper.class);
    private final KnowledgeSpaceService spaces = mock(KnowledgeSpaceService.class);
    private final KnowledgeCollectionService folders = mock(KnowledgeCollectionService.class);
    private final KnowledgePlacementService service = new KnowledgePlacementService(rows, sources, spaces, folders,
            mock(KnowledgeAuditService.class));

    @Test void referenceIsIdempotentAndDoesNotMoveOriginalContent() {
        var source = source(); when(sources.lockById(1L)).thenReturn(source);
        var reference = placement(12L, 4L); when(rows.selectOne(any())).thenReturn(reference);
        assertEquals(reference, service.add(7L, 1L, new KnowledgeSourceLocationRequest(4L, null)));
        verify(rows, never()).insert(any(KnowledgePlacement.class));
        verify(sources, never()).update(any(), any());
        assertEquals(3L, source.getSpaceId());
    }

    @Test void moveOntoExistingReferenceMergesWithoutDuplicatingContent() {
        when(sources.lockById(1L)).thenReturn(source());
        when(rows.selectById(11L)).thenReturn(placement(11L, 3L));
        when(rows.selectOne(any())).thenReturn(placement(12L, 4L));
        assertEquals(12L, service.move(7L, 1L, 11L, new KnowledgeSourceLocationRequest(4L, null)).getId());
        verify(rows).deleteById(11L); verify(rows, never()).insert(any(KnowledgePlacement.class));
        verify(sources).update(isNull(), any());
    }

    @Test void unlinkKeepsOtherReferenceAndProjectsPrimary() {
        when(sources.lockById(1L)).thenReturn(source());
        when(rows.selectById(11L)).thenReturn(placement(11L, 3L));
        when(rows.selectList(any())).thenReturn(List.of(placement(11L, 3L), placement(12L, 4L)));
        service.remove(7L, 1L, 11L);
        verify(rows).deleteById(11L); verify(sources).update(isNull(), any());
        verify(sources, never()).deleteById(anyLong());
    }

    @Test void unlinkLastReferenceIsRejected() {
        when(sources.lockById(1L)).thenReturn(source());
        when(rows.selectById(11L)).thenReturn(placement(11L, 3L));
        when(rows.selectList(any())).thenReturn(List.of(placement(11L, 3L)));
        assertThrows(BusinessException.class, () -> service.remove(7L, 1L, 11L));
        verify(rows, never()).deleteById(anyLong());
    }

    @Test void foreignOwnerCannotReferenceOrMoveContent() {
        when(sources.lockById(1L)).thenReturn(source());
        assertThrows(SecurityException.class, () -> service.add(8L, 1L, new KnowledgeSourceLocationRequest(4L, null)));
        verifyNoInteractions(rows);
    }

    @Test void foreignTargetSpaceIsRejectedBeforeWrite() {
        when(sources.lockById(1L)).thenReturn(source());
        doThrow(new SecurityException()).when(spaces).requireOwnedSpace(7L, 8L);
        assertThrows(SecurityException.class, () -> service.add(7L, 1L, new KnowledgeSourceLocationRequest(8L, null)));
        verifyNoInteractions(rows);
    }

    private KnowledgeSource source() {
        var s = new KnowledgeSource(); s.setId(1L); s.setOwnerUserId(7L); s.setSpaceId(3L); s.setStatus("READY"); return s;
    }
    private KnowledgePlacement placement(Long id, Long spaceId) {
        var p = new KnowledgePlacement(); p.setId(id); p.setSourceId(1L); p.setSpaceId(spaceId); return p;
    }
}
