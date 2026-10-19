package com.example.server.service;

import com.example.server.entity.*;
import com.example.server.mapper.KnowledgeIngestJobMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeIngestJobServiceTest {
    private final KnowledgeIngestJobMapper rows = mock(KnowledgeIngestJobMapper.class);
    private final RocketMQTemplate queue = mock(RocketMQTemplate.class);
    private final KnowledgeIngestJobService service = new KnowledgeIngestJobService(rows, queue, "knowledge-test");
    @Test void readOnlyBenchmarkCannotDispatchOrRecoverCopiedPendingJobs() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "dispatchEnabled", false);
        service.dispatchOutbox();
        verifyNoInteractions(rows, queue);
    }
    @Test void enqueuePersistsBeforeAnyQueueAccess() {
        var source = new KnowledgeSource(); source.setId(1L); source.setMediaId(5L);
        source.setOwnerUserId(7L); source.setStatus("PENDING");
        when(rows.enqueue(7L, 1L, 5L)).thenReturn(1);
        assertTrue(service.enqueue(source)); verify(rows).enqueue(7L, 1L, 5L); verifyNoInteractions(queue);
    }
    @Test void legacyFailedSourceWithoutJobCanBeRetried() {
        var source = new KnowledgeSource(); source.setId(1L); source.setMediaId(5L);
        source.setOwnerUserId(7L); source.setStatus("FAILED"); when(rows.enqueue(7L, 1L, 5L)).thenReturn(1);
        assertTrue(service.enqueue(source)); verifyNoInteractions(queue);
    }
    @Test void readySourceDoesNotReburnExtractionOnDuplicateUpload() {
        var source = new KnowledgeSource(); source.setMediaId(5L); source.setStatus("READY");
        assertFalse(service.enqueue(source)); verifyNoInteractions(rows, queue);
    }
    @Test void queueFailureLeavesPersistedJobEligibleForRetry() {
        var job = new KnowledgeIngestJob(); job.setId(1L);
        when(rows.dispatchable()).thenReturn(List.of(job)); when(rows.reserveDispatch(1L)).thenReturn(1);
        when(queue.syncSend(eq("knowledge-test"), eq(1L), eq(2000L))).thenThrow(new IllegalStateException("broker down"));
        service.dispatchOutbox();
        verify(rows).update(isNull(), any()); verify(rows, never()).deleteById(anyLong());
    }
    @Test void anotherDispatcherReservationAvoidsDoubleSend() {
        var job = new KnowledgeIngestJob(); job.setId(1L);
        when(rows.dispatchable()).thenReturn(List.of(job)); when(rows.reserveDispatch(1L)).thenReturn(0);
        service.dispatchOutbox(); verifyNoInteractions(queue);
    }
}
