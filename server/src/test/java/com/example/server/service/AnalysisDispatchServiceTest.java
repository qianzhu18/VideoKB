package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.service.task.AnalysisTaskService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Fault injection on the submit path: duplicate submissions, rate-limit
 *  rejections, quota-limiter outages, broker failures and event-outage degradation. */
class AnalysisDispatchServiceTest {

    private static final Long MEDIA_ID = 7L;
    private static final Long USER_ID = 1L;
    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String GOAL = "总结视频要点";

    private final AiService aiService = mock(AiService.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RRateLimiter userLimiter = mock(RRateLimiter.class);
    private final RRateLimiter globalLimiter = mock(RRateLimiter.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final AnalysisTaskService taskLedger = mock(AnalysisTaskService.class);

    private AnalysisDispatchService service;
    private MediaFile mediaFile;
    private String activeKey;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter);
        when(redissonClient.getRateLimiter("limit:ai:global")).thenReturn(globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(Duration.ofHours(6))))
                .thenReturn(true);
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(HASH);

        mediaFile = new MediaFile();
        mediaFile.setId(MEDIA_ID);
        mediaFile.setUserId(USER_ID);
        activeKey = AnalysisTaskKeys.active(HASH,
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL));
        service = new AnalysisDispatchService(aiService, mediaService, redisTemplate,
                rocketMQTemplate, redissonClient, taskEventService, taskLedger,
                "video-analysis-topic");
    }

    @Test
    void acceptedSubmissionSendsMessageAndRecordsLedger() {
        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED,
                service.submit(mediaFile, GOAL, null, AnalysisMode.GENERAL));

        ArgumentCaptor<AnalysisTaskMsg> sent = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), (Object) sent.capture());
        assertEquals(MEDIA_ID, sent.getValue().getMediaId());
        assertEquals(AnalysisTaskMsg.START_ANALYSIS, sent.getValue().getAction());
        assertEquals(HASH, sent.getValue().getContentHash());
        verify(taskLedger).onSubmitted(MEDIA_ID, USER_ID, HASH, GOAL, AnalysisMode.GENERAL);
    }

    @Test
    void duplicateInFlightSubmissionIsRejectedWithoutSideEffects() {
        // 台账确认任务真的在跑(QUEUED/PROCESSING):重复提交拒绝,且绝不能清掉
        // 活跃标记——清了会让并发提交双份入队。
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(Duration.ofHours(6))))
                .thenReturn(false);
        when(taskLedger.hasActiveTask(MEDIA_ID)).thenReturn(true);

        assertEquals(AnalysisDispatchService.SubmissionResult.DUPLICATE,
                service.submit(mediaFile, GOAL, null, AnalysisMode.GENERAL));

        verify(taskLedger).hasActiveTask(MEDIA_ID);
        verify(redisTemplate, never()).delete(anyString());
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void staleActiveMarkerIsClearedWhenLedgerShowsNoTaskInFlight() {
        // 进程死亡会把 active 标记残留最多 6 小时(且键按内容哈希共享,一个用户的
        // 残留会挡住另一用户同内容视频的投递)。台账没有在跑记录时,标记就是幽灵:
        // 清掉并照常受理,而不是把内容锁死。
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(Duration.ofHours(6))))
                .thenReturn(false, true);
        when(taskLedger.hasActiveTask(MEDIA_ID)).thenReturn(false);
        when(redisTemplate.delete(activeKey)).thenReturn(true);

        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED,
                service.submit(mediaFile, GOAL, null, AnalysisMode.GENERAL));

        verify(redisTemplate).delete(activeKey);
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), any(Object.class));
        verify(taskLedger).onSubmitted(MEDIA_ID, USER_ID, HASH, GOAL, AnalysisMode.GENERAL);
    }

    @Test
    void duplicateStandsWhenStaleMarkerCannotBeCleared() {
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(Duration.ofHours(6))))
                .thenReturn(false);
        when(taskLedger.hasActiveTask(MEDIA_ID)).thenReturn(false);
        when(redisTemplate.delete(activeKey)).thenReturn(false);

        assertEquals(AnalysisDispatchService.SubmissionResult.DUPLICATE,
                service.submit(mediaFile, GOAL, null, AnalysisMode.GENERAL));

        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void rateLimitedSubmissionReleasesActiveKey() {
        when(userLimiter.tryAcquire()).thenReturn(false);

        assertEquals(AnalysisDispatchService.SubmissionResult.RATE_LIMITED,
                service.submit(mediaFile, GOAL, null, AnalysisMode.GENERAL));

        verify(redisTemplate).delete(activeKey);
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verifyNoInteractions(taskLedger);
    }

    @Test
    void quotaLimiterOutageFailsTheSubmissionAndReleasesActiveKey() {
        // tryAcquireQuota rethrows infrastructure errors; submit must convert
        // them into a clean FAILED (never an accept-without-quota) and roll
        // back the active key so the user can retry immediately.
        when(userLimiter.tryAcquire()).thenThrow(new RuntimeException("redis down"));

        assertEquals(AnalysisDispatchService.SubmissionResult.FAILED,
                service.submit(mediaFile, GOAL, null, AnalysisMode.GENERAL));

        verify(redisTemplate).delete(activeKey);
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verifyNoInteractions(taskLedger);
    }

    @Test
    void mqOutageRollsBackActiveKeyAndStagedRevision() {
        AgentFeedback revision = mock(AgentFeedback.class);
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));
        // Revision tasks derive their idempotency key from the mediaId rather
        // than the content hash (mirroring AnalysisDispatchService).
        String revisionActiveKey = AnalysisTaskKeys.active("media-" + MEDIA_ID,
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL));

        assertEquals(AnalysisDispatchService.SubmissionResult.FAILED,
                service.submit(mediaFile, GOAL, revision, AnalysisMode.GENERAL));

        verify(redisTemplate).delete(revisionActiveKey);
        verify(aiService).stageRevision(revision, AnalysisMode.GENERAL);
        verify(aiService).cancelStagedRevision(MEDIA_ID, GOAL, AnalysisMode.GENERAL);
        verifyNoInteractions(taskLedger);
    }

    @Test
    void eventOutageDoesNotMaskAcceptedSubmission() {
        doThrow(new RuntimeException("redis pubsub down"))
                .when(taskEventService).publishAnalysis(anyLong(), anyString(), any(),
                        any(), any());

        // The broker already accepted the message; a notification failure must
        // not disguise the task as a dispatch failure.
        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED,
                service.submit(mediaFile, GOAL, null, AnalysisMode.GENERAL));
        verify(taskLedger).onSubmitted(MEDIA_ID, USER_ID, HASH, GOAL, AnalysisMode.GENERAL);
    }
}
