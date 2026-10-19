package com.example.server.consumer;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStage;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentLoopService;
import com.example.server.service.AiService;
import com.example.server.service.FailedAnalysisTaskService;
import com.example.server.service.KnowledgeSegmentIndexService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import com.example.server.service.task.AnalysisTaskService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fault-injection coverage of the consume path: duplicate delivery, transient
 * failure retry, exhausted-delivery dead lettering, permanent failure, budget
 * exhaustion, poison-message convergence, revision messages, and the
 * content-level reuse that proves "a new batch never re-runs succeeded videos".
 */
class VideoAnalysisConsumerTest {

    private static final Long MEDIA_ID = 7L;
    private static final Long SOURCE_MEDIA_ID = 5L;
    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String GOAL = "总结视频要点";
    private static final String DEAD_TOPIC = "video-analysis-dead-topic";

    private final AiService aiService = mock(AiService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final FailedAnalysisTaskService failedTaskService = mock(FailedAnalysisTaskService.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final AnalysisTaskService taskLedger = mock(AnalysisTaskService.class);
    private final KnowledgeSegmentIndexService segmentIndexService = mock(KnowledgeSegmentIndexService.class);

    private VideoAnalysisConsumer consumer;

    @BeforeEach
    void setUp() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(redissonClient.getLock(AnalysisTaskKeys.lock(HASH, AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL)))).thenReturn(mock(RLock.class));
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(mediaService.exists(MEDIA_ID)).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(null);
        consumer = new VideoAnalysisConsumer(aiService, redissonClient, redisTemplate,
                checkpointService, rocketMQTemplate, failedTaskService, mediaService,
                taskEventService, taskLedger, DEAD_TOPIC);
    }

    private AnalysisTaskMsg msg() {
        return new AnalysisTaskMsg(MEDIA_ID, AnalysisTaskMsg.START_ANALYSIS, HASH, GOAL,
                AnalysisMode.GENERAL.name());
    }

    private AnalysisTaskMsg revisionMsg() {
        return new AnalysisTaskMsg(MEDIA_ID, AnalysisTaskMsg.REVISE_ANALYSIS, HASH, GOAL,
                AnalysisMode.GENERAL.name());
    }

    private AgentState completedState() {
        return new AgentState(GOAL,
                new AgentState.AgentPlan("plan", List.of()),
                new AnalysisResult("标题", List.of("结论"), List.of(), List.of(), List.of()),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()),
                1);
    }

    private void stubAttempt(long attempt) {
        when(valueOps.increment(anyString())).thenReturn(attempt);
    }

    private String completedKey() {
        return AnalysisTaskKeys.completed(HASH,
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL));
    }

    @Test
    void duplicateDeliveryWhileLockHeldIsSkippedWithoutAnalysis() {
        when(lock.tryLock()).thenReturn(false);

        assertDoesNotThrow(() -> consumer.onMessage(msg()));

        verify(aiService, never()).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));
        verify(valueOps, never()).increment(anyString());
        verify(taskLedger, never()).onStarted(anyLong(), any(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void deletedMediaIsDiscardedBeforeCountingAttempts() {
        when(mediaService.exists(MEDIA_ID)).thenReturn(false);

        assertDoesNotThrow(() -> consumer.onMessage(msg()));

        verify(aiService, never()).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void completedContentIsReusedWithoutRerunningAnalysis() {
        // "A new batch must not re-run succeeded videos": a new mediaId with the
        // same content hits the completed key and reuses the stored result.
        stubAttempt(1);
        when(valueOps.get(completedKey())).thenReturn(String.valueOf(SOURCE_MEDIA_ID));
        when(checkpointService.loadResult(SOURCE_MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(completedState());
        when(aiService.reuseResult(eq(MEDIA_ID), eq(SOURCE_MEDIA_ID), any(AgentState.class),
                eq(AnalysisMode.GENERAL))).thenReturn(true);

        consumer.onMessage(msg());

        verify(aiService, never()).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));
        verify(aiService).indexKnowledge(MEDIA_ID);
        verify(taskLedger).onCompleted(eq(MEDIA_ID), eq(HASH), eq(GOAL), eq(AnalysisMode.GENERAL),
                eq("COMPLETED_REUSED"));
        // The reuse path must not drop the completed key — the next task with the
        // same content should keep hitting it. The finally block only clears
        // active/attempts.
        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate).delete(anyList());
    }

    @Test
    void successfulAnalysisWritesCompletedKeyAndLedger() {
        stubAttempt(1);
        when(checkpointService.loadResult(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(completedState());

        consumer.onMessage(msg());

        verify(aiService).asyncAnalyze(MEDIA_ID, GOAL, AnalysisMode.GENERAL);
        verify(valueOps).set(eq(completedKey()), eq(String.valueOf(MEDIA_ID)),
                eq(Duration.ofDays(7)));
        verify(taskLedger).onCompleted(eq(MEDIA_ID), eq(HASH), eq(GOAL), eq(AnalysisMode.GENERAL),
                eq("COMPLETED"));
        // Success clears the idempotency keys so a later revision can resubmit.
        verify(redisTemplate).delete(anyList());
    }

    @Test
    void revisionMessageRunsStagedRevisionLifecycle() {
        stubAttempt(1);
        when(checkpointService.beginStagedRevision(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(true);
        when(checkpointService.loadResult(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(completedState());

        consumer.onMessage(revisionMsg());

        verify(aiService).asyncAnalyze(MEDIA_ID, GOAL, AnalysisMode.GENERAL);
        // The stale completed key must be dropped before re-analysis, otherwise
        // a concurrent delivery would instantly "reuse" the old result.
        verify(redisTemplate).delete(completedKey());
        verify(checkpointService).completeStagedRevision(MEDIA_ID, GOAL, AnalysisMode.GENERAL);
        verify(valueOps).set(eq(completedKey()), eq(String.valueOf(MEDIA_ID)),
                eq(Duration.ofDays(7)));
        verify(taskLedger).onCompleted(eq(MEDIA_ID), eq(HASH), eq(GOAL), eq(AnalysisMode.GENERAL),
                eq("COMPLETED"));
    }

    @Test
    void revisionWithoutStagedStateFailsOverToMqRetry() {
        stubAttempt(1);
        when(checkpointService.beginStagedRevision(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(false);

        // Missing staged revision state is transient by design: the exception
        // rejects the ACK and lets the broker redeliver.
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(revisionMsg()));

        verify(aiService, never()).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));
        verify(checkpointService, never()).completeStagedRevision(anyLong(), anyString(),
                any(AnalysisMode.class));
        verify(taskLedger).onRetryScheduled(eq(MEDIA_ID), eq(HASH), eq(GOAL),
                eq(AnalysisMode.GENERAL), eq(1));
    }

    @Test
    void transientFailureOnEarlyAttemptSchedulesMqRetry() {
        stubAttempt(1);
        doThrow(new IllegalStateException("ASR timeout"))
                .when(aiService).asyncAnalyze(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        // Throwing = refusing the ACK; RocketMQ redelivers after a delay level.
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(msg()));

        verify(checkpointService).saveStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL, TaskStage.RETRYING);
        verify(taskLedger).onRetryScheduled(eq(MEDIA_ID), eq(HASH), eq(GOAL),
                eq(AnalysisMode.GENERAL), eq(1));
        // The branch that matters renews the active key specifically (the
        // attempts-key renewal is routine and counted separately).
        verify(redisTemplate).expire(
                eq(AnalysisTaskKeys.active(AnalysisTaskKeys.mediaScope(MEDIA_ID),
                        AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL))),
                eq(Duration.ofHours(6)));
        // Idempotency keys must survive the retry window, or the frontend would
        // consider the task finished and submit the same work again.
        verify(redisTemplate, never()).delete(anyList());
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(failedTaskService, never()).record(any(), anyLong(), any());
        verify(lock).unlock();
    }

    @Test
    void exhaustedAttemptsConvergeToDeadLetterAndLedger() {
        stubAttempt(3);
        doThrow(new IllegalStateException("ASR keeps timing out"))
                .when(aiService).asyncAnalyze(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        // The dead-letter path returns normally (ACK) instead of relying on
        // endless broker redeliveries.
        assertDoesNotThrow(() -> consumer.onMessage(msg()));

        ArgumentCaptor<AnalysisTaskMsg> deadLetter = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(rocketMQTemplate).convertAndSend(eq(DEAD_TOPIC), (Object) deadLetter.capture());
        assertEquals(MEDIA_ID, deadLetter.getValue().getMediaId());
        verify(failedTaskService).record(any(AnalysisTaskMsg.class), eq(3L), any());
        verify(checkpointService).saveStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL, TaskStage.DEAD_LETTERED);
        verify(taskLedger).onFailed(eq(MEDIA_ID), eq(HASH), eq(GOAL), eq(AnalysisMode.GENERAL),
                eq("DEAD_LETTERED"), eq("IllegalStateException"), anyString());
        verify(redisTemplate).delete(anyList());
    }

    @Test
    void permanentFailureSkipsRetryAndDeadLettersImmediately() {
        stubAttempt(1);
        doThrow(new IllegalStateException("invalid argument",
                new IllegalArgumentException("unsupported goal format")))
                .when(aiService).asyncAnalyze(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertDoesNotThrow(() -> consumer.onMessage(msg()));

        verify(checkpointService, never()).saveStage(anyLong(), anyString(), any(AnalysisMode.class),
                eq(TaskStage.RETRYING));
        verify(failedTaskService).record(any(AnalysisTaskMsg.class), eq(1L), any());
        verify(rocketMQTemplate).convertAndSend(eq(DEAD_TOPIC), any(AnalysisTaskMsg.class));
    }

    @Test
    void budgetExhaustionIsTerminalWithoutDeadLetter() {
        stubAttempt(1);
        doThrow(new AgentLoopService.BudgetExceededException("budget exhausted"))
                .when(aiService).asyncAnalyze(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        // Budget exhaustion ACKs directly: redelivery would only burn the same
        // budget again, and there is nothing to put on the dead-letter topic.
        assertDoesNotThrow(() -> consumer.onMessage(msg()));

        verify(checkpointService).saveStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL,
                TaskStage.BUDGET_EXHAUSTED);
        verify(taskLedger).onFailed(eq(MEDIA_ID), eq(HASH), eq(GOAL), eq(AnalysisMode.GENERAL),
                eq("BUDGET_EXHAUSTED"), eq("BudgetExceeded"), eq("budget exhausted"));
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(failedTaskService, never()).record(any(), anyLong(), any());
    }

    @Test
    void poisonMessageIsRecordedReleasedAndAcked() {
        AnalysisTaskMsg poison = new AnalysisTaskMsg(MEDIA_ID, "EXPLODE", HASH, GOAL,
                AnalysisMode.GENERAL.name());

        assertDoesNotThrow(() -> consumer.onMessage(poison));

        verify(failedTaskService).record(eq(poison), eq(0L), any());
        verify(rocketMQTemplate).convertAndSend(eq(DEAD_TOPIC), eq((Object) poison));
        verify(taskLedger).onReleased(eq(MEDIA_ID), eq(HASH), eq(GOAL), eq(AnalysisMode.GENERAL));
        verify(checkpointService).saveStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL, TaskStage.DEAD_LETTERED);
        // A poison message must also release the idempotency keys, otherwise
        // every resubmission within 6 hours is rejected as DUPLICATE.
        verify(redisTemplate).delete(anyList());
    }

    @Test
    void poisonMessageRefusesAckWhenBothSinksAreDown() {
        AnalysisTaskMsg poison = new AnalysisTaskMsg(MEDIA_ID, "EXPLODE", HASH, GOAL,
                AnalysisMode.GENERAL.name());
        doThrow(new RuntimeException("db down")).when(failedTaskService)
                .record(any(), anyLong(), any());
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));

        // With both the ledger and the dead-letter topic unavailable, ACKing
        // would silently drop the message: the ACK must be refused and the
        // message redelivered until the dependencies recover.
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(poison));
    }
}
