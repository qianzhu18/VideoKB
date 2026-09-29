package com.example.server.service.task;

import com.example.server.dto.AnalysisMode;
import com.example.server.entity.AnalysisTask;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.AnalysisTaskMapper;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ledger behavior: happy-path transitions for every event, plus the fault paths
 *  (illegal transition, database outage, corrupt state, missing identity). */
class AnalysisTaskServiceTest {

    private static final Long MEDIA_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String GOAL = "总结视频要点";

    private final AnalysisTaskMapper taskMapper = mock(AnalysisTaskMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final AnalysisTaskService service = new AnalysisTaskService(taskMapper, mediaFileMapper);

    private AnalysisTask row(Long id, String state) {
        AnalysisTask row = new AnalysisTask();
        row.setId(id);
        row.setOwnerUserId(USER_ID);
        row.setMediaId(MEDIA_ID);
        row.setGoalDigest("digest");
        row.setMode("GENERAL");
        row.setContentHash(HASH);
        row.setState(state);
        row.setAttemptCount(1);
        return row;
    }

    @Test
    void submittedTaskInsertsQueuedRowWithOwnerHint() {
        when(taskMapper.selectOne(any())).thenReturn(null);

        service.onSubmitted(MEDIA_ID, USER_ID, HASH, GOAL, AnalysisMode.GENERAL);

        ArgumentCaptor<AnalysisTask> captor = ArgumentCaptor.forClass(AnalysisTask.class);
        verify(taskMapper).insert(captor.capture());
        AnalysisTask inserted = captor.getValue();
        assertEquals("QUEUED", inserted.getState());
        assertEquals(USER_ID, inserted.getOwnerUserId());
        assertEquals("QUEUED", inserted.getLastStage());
        assertEquals(0, inserted.getAttemptCount());
        // With the owner provided by the caller there must be no media lookup.
        verify(mediaFileMapper, never()).selectById(any(Long.class));
    }

    @Test
    void startedTaskMovesQueuedRowToProcessing() {
        AnalysisTask existing = row(11L, "QUEUED");
        when(taskMapper.selectOne(any())).thenReturn(existing);

        service.onStarted(MEDIA_ID, USER_ID, HASH, GOAL, AnalysisMode.GENERAL, 2);

        verify(taskMapper).updateById(existing);
        assertEquals("PROCESSING", existing.getState());
        assertEquals(2, existing.getAttemptCount());
        assertEquals("CONSUMING", existing.getLastStage());
    }

    @Test
    void retryScheduledMovesProcessingRowBackToQueued() {
        AnalysisTask processing = row(12L, "PROCESSING");
        when(taskMapper.selectOne(any())).thenReturn(processing);

        service.onRetryScheduled(MEDIA_ID, HASH, GOAL, AnalysisMode.GENERAL, 2);

        assertEquals("QUEUED", processing.getState());
        assertEquals(2, processing.getAttemptCount());
        assertEquals("RETRYING", processing.getLastStage());
    }

    @Test
    void completedRowClearsErrorFieldsAndRecordsStage() {
        AnalysisTask processing = row(13L, "PROCESSING");
        processing.setErrorType("IllegalStateException");
        processing.setErrorMessage("boom");
        when(taskMapper.selectOne(any())).thenReturn(processing);

        service.onCompleted(MEDIA_ID, HASH, GOAL, AnalysisMode.GENERAL, "COMPLETED_REUSED");

        assertEquals("COMPLETED", processing.getState());
        assertEquals("COMPLETED_REUSED", processing.getLastStage());
        assertNull(processing.getErrorType());
        assertNull(processing.getErrorMessage());
    }

    @Test
    void requeuedRowResetsFailedStateForRedelivery() {
        AnalysisTask failed = row(14L, "FAILED");
        failed.setErrorType("IllegalStateException");
        failed.setErrorMessage("exhausted");
        when(taskMapper.selectOne(any())).thenReturn(failed);

        service.onRequeued(MEDIA_ID, HASH, GOAL, AnalysisMode.GENERAL);

        assertEquals("QUEUED", failed.getState());
        assertEquals(0, failed.getAttemptCount());
        assertEquals("MANUAL_REPLAY", failed.getLastStage());
        assertNull(failed.getErrorType());
    }

    @Test
    void releasedRowIsVoidedAsFailed() {
        AnalysisTask queued = row(15L, "QUEUED");
        when(taskMapper.selectOne(any())).thenReturn(queued);

        service.onReleased(MEDIA_ID, HASH, GOAL, AnalysisMode.GENERAL);

        assertEquals("FAILED", queued.getState());
        assertEquals("DEAD_LETTERED", queued.getLastStage());
        assertEquals("InvalidMessage", queued.getErrorType());
    }

    @Test
    void ownerResolvedFromMediaWhenHintMissing() {
        when(taskMapper.selectOne(any())).thenReturn(null);
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(USER_ID);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);

        service.onStarted(MEDIA_ID, null, HASH, GOAL, AnalysisMode.GENERAL, 1);

        ArgumentCaptor<AnalysisTask> captor = ArgumentCaptor.forClass(AnalysisTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(USER_ID, captor.getValue().getOwnerUserId());
    }

    @Test
    void illegalTransitionKeepsRowUntouchedAndNeverThrows() {
        // A COMPLETED row receiving RETRY_SCHEDULED: the illegal transition only
        // warns — the row is not rewritten and nothing propagates.
        AnalysisTask completed = row(16L, "COMPLETED");
        when(taskMapper.selectOne(any())).thenReturn(completed);

        assertDoesNotThrow(() ->
                service.onRetryScheduled(MEDIA_ID, HASH, GOAL, AnalysisMode.GENERAL, 2));

        verify(taskMapper, never()).updateById(any(AnalysisTask.class));
        assertEquals("COMPLETED", completed.getState());
    }

    @Test
    void ledgerOutageIsSwallowedNotPropagated() {
        when(taskMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() ->
                service.onCompleted(MEDIA_ID, HASH, GOAL, AnalysisMode.GENERAL, "COMPLETED"));
    }

    @Test
    void blankGoalSkipsLedgerEntirely() {
        service.onStarted(MEDIA_ID, USER_ID, HASH, "  ", AnalysisMode.GENERAL, 1);
        verify(taskMapper, never()).selectOne(any());
    }

    @Test
    void failedTaskRecordsTruncatedError() {
        AnalysisTask processing = row(17L, "PROCESSING");
        when(taskMapper.selectOne(any())).thenReturn(processing);

        service.onFailed(MEDIA_ID, HASH, GOAL, AnalysisMode.GENERAL,
                "DEAD_LETTERED", "Boom", "x".repeat(2_000));

        assertEquals("FAILED", processing.getState());
        assertEquals("DEAD_LETTERED", processing.getLastStage());
        assertEquals(1_000, processing.getErrorMessage().length());
    }

    @Test
    void corruptStateValueDegradesToNotStarted() {
        AnalysisTask corrupt = row(18L, "SOME_FUTURE_STATE");
        when(taskMapper.selectOne(any())).thenReturn(corrupt);

        service.onSubmitted(MEDIA_ID, USER_ID, HASH, GOAL, AnalysisMode.GENERAL);

        // Corrupt data must not wedge the task: it is re-accepted as NOT_STARTED.
        assertEquals("QUEUED", corrupt.getState());
    }

    @Test
    void manifestJoinsFilenamesAndMarksDeletedMedia() {
        AnalysisTask kept = row(21L, "COMPLETED");
        AnalysisTask orphan = row(22L, "FAILED");
        orphan.setMediaId(99L);
        when(taskMapper.selectList(any())).thenReturn(List.of(kept, orphan));
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setFilename("demo.mp4");
        when(mediaFileMapper.selectBatchIds(any())).thenReturn(List.of(media));

        List<AnalysisTaskService.AnalysisTaskView> manifest = service.manifest(USER_ID);

        assertEquals(2, manifest.size());
        assertEquals("demo.mp4", manifest.get(0).filename());
        assertEquals("(媒体已删除)", manifest.get(1).filename());
        assertEquals("COMPLETED", manifest.get(0).state());
    }

    @Test
    void emptyManifestSkipsMediaJoin() {
        when(taskMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.manifest(USER_ID).isEmpty());
        verify(mediaFileMapper, never()).selectBatchIds(any());
        verify(taskMapper, never()).insert(any(AnalysisTask.class));
        verify(taskMapper, never()).updateById(any(AnalysisTask.class));
        verify(mediaFileMapper, never()).selectById(any(Long.class));
    }

    @Test
    void consumeStartConvergesOntoWinnerRowWhenInsertRacesSubmit() {
        // Local-queue delivery is millisecond-fast: CONSUME_START's SELECT misses the
        // SUBMIT row (not yet committed), its INSERT hits the unique key. The write
        // must converge onto the winning row instead of being swallowed — otherwise
        // the ledger stays QUEUED and COMPLETE is later rejected as illegal.
        AnalysisTask winner = row(9L, "QUEUED");
        when(taskMapper.selectOne(any())).thenReturn(null, winner);
        when(taskMapper.insert(any(AnalysisTask.class)))
                .thenThrow(new RuntimeException("Duplicate entry '7-digest' for key 'analysis_tasks.uk_analysis_task_media'"));

        service.onStarted(MEDIA_ID, USER_ID, HASH, GOAL, AnalysisMode.GENERAL, 1);

        ArgumentCaptor<AnalysisTask> captor = ArgumentCaptor.forClass(AnalysisTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(9L, captor.getValue().getId());
        assertEquals("PROCESSING", captor.getValue().getState());
    }

    @Test
    void hasActiveTaskSeesQueuedAndProcessingRowsOnly() {
        // 投递侧用这个判定幂等键是不是僵尸残留：台账说在跑才算在跑。
        when(taskMapper.selectList(any()))
                .thenReturn(List.of(row(9L, "QUEUED")))
                .thenReturn(List.of(row(9L, "PROCESSING")))
                .thenReturn(List.of(row(9L, "FAILED")))
                .thenReturn(List.of());
        assertTrue(service.hasActiveTask(MEDIA_ID));
        assertTrue(service.hasActiveTask(MEDIA_ID));
        assertTrue(!service.hasActiveTask(MEDIA_ID));
        assertTrue(!service.hasActiveTask(MEDIA_ID));
    }

    @Test
    void hasActiveTaskHandlesMissingMediaAndNullId() {
        when(taskMapper.selectList(any())).thenReturn(List.of());
        assertTrue(!service.hasActiveTask(999L));
        assertTrue(!service.hasActiveTask(null));
    }
}
