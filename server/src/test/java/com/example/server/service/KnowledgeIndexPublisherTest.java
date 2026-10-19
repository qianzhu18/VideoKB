package com.example.server.service;

import com.example.server.entity.*;
import com.example.server.mapper.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeIndexPublisherTest {
    private final KnowledgeSourceMapper sources = mock(KnowledgeSourceMapper.class);
    private final KnowledgeSourceVersionMapper versions = mock(KnowledgeSourceVersionMapper.class);
    private final KnowledgeIndexPublisher publisher = new KnowledgeIndexPublisher(sources, versions);

    @Test void failedBuildKeepsPublishedGenerationReady() {
        var source = source("READY", 1); when(sources.lockById(1L)).thenReturn(source);
        publisher.fail(source, version(2), "vector store unavailable");
        verify(versions).update(isNull(), any()); verify(sources, never()).update(any(), any());
        assertEquals(1, source.getCurrentVersion()); assertEquals("READY", source.getStatus());
    }
    @Test void deletionDuringBuildCannotResurrectSource() {
        var source = source("DELETED", 1); when(sources.lockById(1L)).thenReturn(source);
        assertThrows(IllegalStateException.class, () -> publisher.publish(source, version(2)));
        verify(sources, never()).update(any(), any()); verifyNoInteractions(versions);
    }
    @Test void staleBuildCannotReplaceNewerPublishedGeneration() {
        var source = source("READY", 3); when(sources.lockById(1L)).thenReturn(source);
        assertThrows(IllegalStateException.class, () -> publisher.publish(source, version(2)));
        verifyNoInteractions(versions);
    }
    @Test void publishUpdatesBothVisibilityRows() {
        var source = source("READY", 1); when(sources.lockById(1L)).thenReturn(source);
        publisher.publish(source, version(2));
        verify(versions).update(isNull(), any()); verify(sources).update(isNull(), any());
    }
    private KnowledgeSource source(String status, int no) {
        var s = new KnowledgeSource(); s.setId(1L); s.setStatus(status); s.setCurrentVersion(no); return s;
    }
    private KnowledgeSourceVersion version(int no) {
        var v = new KnowledgeSourceVersion(); v.setId(12L); v.setSourceId(1L); v.setVersionNo(no); return v;
    }
}
