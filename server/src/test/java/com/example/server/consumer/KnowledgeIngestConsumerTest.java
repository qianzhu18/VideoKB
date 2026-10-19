package com.example.server.consumer;

import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.service.KnowledgeIngestPipeline;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeIngestConsumerTest {
    private final KnowledgeIngestJobMapper jobs = mock(KnowledgeIngestJobMapper.class);
    private final KnowledgeSourceMapper sources = mock(KnowledgeSourceMapper.class);
    private final KnowledgeIngestPipeline pipeline = mock(KnowledgeIngestPipeline.class);
    private final RedissonClient locks = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);

    private KnowledgeIngestConsumer consumer(String sourceStatus, boolean force, int attempt) {
        when(locks.getLock(anyString())).thenReturn(lock); when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); when(jobs.start(1L)).thenReturn(1);
        var job = new KnowledgeIngestJob(); job.setId(1L); job.setSourceId(2L); job.setMediaId(3L);
        job.setAttemptCount(attempt); job.setForceRebuild(force); when(jobs.selectById(1L)).thenReturn(job);
        var source = new KnowledgeSource(); source.setId(2L); source.setStatus(sourceStatus);
        when(sources.selectById(2L)).thenReturn(source);
        return new KnowledgeIngestConsumer(jobs, pipeline, locks, sources);
    }
    @Test void alreadyPublishedImportDoesNotReburnEmbedding() {
        consumer("READY", false, 1).onMessage(1L);
        verifyNoInteractions(pipeline); verify(jobs).update(isNull(), any()); verify(lock).unlock();
    }
    @Test void explicitRebuildProcessesReadySource() {
        consumer("READY", true, 1).onMessage(1L);
        verify(pipeline).ingest(eq(3L), any()); verify(jobs).update(isNull(), any());
    }
    @Test void duplicateDeliveryDoesNotRunPipeline() {
        var consumer = consumer("PENDING", false, 1); when(jobs.start(1L)).thenReturn(0);
        consumer.onMessage(1L); verifyNoInteractions(pipeline);
    }
    @Test void deletedSourceCancelsWithoutPreparation() {
        consumer("DELETED", false, 1).onMessage(1L); verifyNoInteractions(pipeline);
    }
    @Test void failureIsPersistedAndMessageCanBeAcknowledged() {
        var consumer = consumer("PENDING", false, 3);
        doThrow(new IllegalStateException("model offline")).when(pipeline).ingest(anyLong(), any());
        consumer.onMessage(1L); verify(jobs).update(isNull(), any()); verify(lock).unlock();
    }
}
