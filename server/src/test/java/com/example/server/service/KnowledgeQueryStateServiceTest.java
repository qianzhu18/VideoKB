package com.example.server.service;

import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeQueryStateServiceTest {
    private final KnowledgeScopeResolver scopes = mock(KnowledgeScopeResolver.class);
    private final KnowledgePlacementService placements = mock(KnowledgePlacementService.class);
    private final KnowledgeSourceMapper sources = mock(KnowledgeSourceMapper.class);
    private final KnowledgeCollectionService folders = mock(KnowledgeCollectionService.class);
    private final KnowledgeQueryStateService service = new KnowledgeQueryStateService(scopes, placements, sources, folders);
    private KnowledgeSource source(long id, String status) {
        var s = new KnowledgeSource(); s.setId(id); s.setStatus(status); return s;
    }
    private KnowledgePlacement placement(long source, long folder) {
        var p = new KnowledgePlacement(); p.setSourceId(source); p.setCollectionId(folder); return p;
    }
    @Test void pendingAndFailedAreNotEmptyEvidenceAndDoNotCountSiblingFolders() {
        when(scopes.effectiveSpace(7L, 3L, 10L)).thenReturn(3L);
        when(scopes.resolve(7L, 3L, 10L)).thenReturn(new KnowledgeQueryScope(7L, Map.of()));
        when(folders.subtreeIds(3L, 10L)).thenReturn(List.of(10L, 11L));
        when(placements.inLocation(3L, 10L, true)).thenReturn(List.of(placement(1, 11), placement(2, 12)));
        when(sources.selectList(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) invocation.getArgument(0);
            wrapper.getSqlSegment();
            assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));
            assertFalse(wrapper.getParamNameValuePairs().containsValue(2L));
            return List.of(source(1, "PENDING"));
        });
        var state = service.describe(7L, 3L, 10L);
        assertEquals("NOT_READY", state.status()); assertEquals(1, state.pendingSources());
        doReturn(List.of(source(1, "FAILED"))).when(sources).selectList(any());
        assertEquals("FAILED", service.describe(7L, 3L, 10L).status());
    }
    @Test void readablePublishedGenerationIsCountedEvenWhenRebuildIsPending() {
        when(scopes.effectiveSpace(7L, 3L, null)).thenReturn(3L);
        when(scopes.resolve(7L, 3L, null)).thenReturn(new KnowledgeQueryScope(7L, Map.of(1L, new KnowledgeQueryScope.Generation(20L, 2))));
        when(placements.inLocation(3L, null, true)).thenReturn(List.of(placement(1, 10), placement(2, 11)));
        when(sources.selectList(any())).thenReturn(List.of(source(1, "READY"), source(2, "PENDING")));
        var state = service.describe(7L, 3L, null);
        assertEquals("PARTIAL", state.status()); assertEquals(1, state.readySources());
    }
    @Test void unauthorizedScopeCannotLeakProcessingState() {
        when(scopes.effectiveSpace(8L, 3L, null)).thenThrow(new SecurityException());
        assertThrows(SecurityException.class, () -> service.describe(8L, 3L, null));
        verifyNoInteractions(placements, sources);
    }
    @Test void unreadyAskAndStreamDoNotCallRetrievalOrModel() {
        var states = mock(KnowledgeQueryStateService.class);
        var search = mock(KnowledgeSearchService.class); var generator = mock(KnowledgeAnswerGenerator.class);
        var answer = new KnowledgeAnswerService(search, generator);
        org.springframework.test.util.ReflectionTestUtils.setField(answer, "queryStates", states);
        when(states.describe(7L, 3L, null)).thenReturn(new KnowledgeQueryState(3L, null, "NOT_READY", 0, 1, 0));
        var request = new KnowledgeAskRequest(3L, null, "缓存", 8, "hybrid");
        assertEquals("NOT_READY", answer.ask(7L, request).answerability());
        assertEquals("NOT_READY", answer.askStreaming(7L, request, ignored -> {}, ignored -> {}).answerability());
        verifyNoInteractions(search, generator);
    }
    @Test void omittedSpaceMeansDefaultAndCollectionNeedsExplicitSpace() {
        var spaces = mock(KnowledgeSpaceService.class);
        var resolver = new KnowledgeScopeResolver(spaces, folders, mock(KnowledgePlacementMapper.class), sources, mock(KnowledgeSourceVersionMapper.class));
        var defaultSpace = new KnowledgeSpace(); defaultSpace.setId(4L);
        when(spaces.defaultSpaceForUser(7L)).thenReturn(defaultSpace);
        assertEquals(4L, resolver.effectiveSpace(7L, null, null));
        verify(spaces).requireOwnedSpace(7L, 4L);
        assertThrows(com.example.server.exception.BusinessException.class, () -> resolver.effectiveSpace(7L, null, 10L));
    }
}
