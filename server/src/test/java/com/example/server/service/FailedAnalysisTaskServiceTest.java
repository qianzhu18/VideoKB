package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.FailedAnalysisTask;
import com.example.server.mapper.FailedAnalysisTaskMapper;
import com.example.server.service.task.AnalysisTaskService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Idempotency of the failure ledger and replay: duplicate replays, placeholder
 *  records, active-task conflicts and the trade-offs around bookkeeping failures. */
class FailedAnalysisTaskServiceTest {

    private static final Long MEDIA_ID = 7L;
    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String GOAL = "总结视频要点";

    private final FailedAnalysisTaskMapper taskMapper = mock(FailedAnalysisTaskMapper.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final AnalysisTaskService taskLedger = mock(AnalysisTaskService.class);

    private FailedAnalysisTaskService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(Duration.ofHours(6))))
                .thenReturn(true);
        service = new FailedAnalysisTaskService(taskMapper, rocketMQTemplate, redisTemplate,
                taskEventService, taskLedger, "video-analysis-topic");
    }

    private FailedAnalysisTask failedTask(long id, String status) {
        FailedAnalysisTask task = new FailedAnalysisTask();
        task.setId(id);
        task.setMediaId(MEDIA_ID);
        task.setAction(AnalysisTaskMsg.START_ANALYSIS);
        task.setMode("GENERAL");
        task.setContentHash(HASH);
        task.setUserGoal(GOAL);
        task.setAttemptCount(3);
        task.setErrorType("IllegalStateException");
        task.setErrorMessage("三次投递均失败");
        task.setStatus(status);
        return task;
    }

    @Test
    void recordTruncatesColumnsAndMasksSecrets() {
        AnalysisTaskMsg msg = new AnalysisTaskMsg(MEDIA_ID, AnalysisTaskMsg.START_ANALYSIS,
                HASH, "g".repeat(600), "GENERAL");

        service.record(msg, 3, new RuntimeException(
                "call failed: Authorization=Bearer abcdefghijklmnop and api-key=s3cr3tkeyvalue"));

        ArgumentCaptor<FailedAnalysisTask> captor = ArgumentCaptor.forClass(FailedAnalysisTask.class);
        verify(taskMapper).insert(captor.capture());
        FailedAnalysisTask row = captor.getValue();
        assertEquals(500, row.getUserGoal().length());
        assertEquals(3, row.getAttemptCount());
        assertEquals("FAILED", row.getStatus());
        String message = row.getErrorMessage();
        assertEquals(true, message.contains("Bearer ****"));
        assertEquals(true, message.contains("api-key=****"));
    }

    @Test
    void replayRequeuesFailedTaskIdempotently() {
        FailedAnalysisTask task = failedTask(3L, "FAILED");
        when(taskMapper.selectById(3L)).thenReturn(task);
        when(taskMapper.updateById(any(FailedAnalysisTask.class))).thenReturn(1);

        service.replay(3L);

        ArgumentCaptor<AnalysisTaskMsg> sent = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), (Object) sent.capture());
        assertEquals(MEDIA_ID, sent.getValue().getMediaId());
        verify(redisTemplate).delete(attemptsKey());
        assertEquals("REQUEUED", task.getStatus());
        verify(taskLedger).onRequeued(eq(MEDIA_ID), eq(HASH), eq(GOAL), any());
    }

    @Test
    void replayRejectsAlreadyRequeuedTask() {
        when(taskMapper.selectById(4L)).thenReturn(failedTask(4L, "REQUEUED"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.replay(4L));
        assertEquals("该失败任务已经重放", error.getMessage());
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void replayRejectsMissingTask() {
        when(taskMapper.selectById(404L)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () -> service.replay(404L));
    }

    @Test
    void replayRejectsPlaceholderFromPoisonMessage() {
        FailedAnalysisTask placeholder = failedTask(5L, "FAILED");
        placeholder.setMediaId(-1L);
        when(taskMapper.selectById(5L)).thenReturn(placeholder);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.replay(5L));
        assertEquals("该记录来自非法任务消息，缺少可重放的原始参数", error.getMessage());
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void replayRejectsWhenSameTaskStillActive() {
        when(taskMapper.selectById(6L)).thenReturn(failedTask(6L, "FAILED"));
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(Duration.ofHours(6))))
                .thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.replay(6L));
        assertEquals("相同任务正在处理中", error.getMessage());
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void replayDispatchFailureReleasesActiveKey() {
        when(taskMapper.selectById(7L)).thenReturn(failedTask(7L, "FAILED"));
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));

        assertThrows(RuntimeException.class, () -> service.replay(7L));
        verify(redisTemplate).delete(activeKey());
        assertEquals("FAILED", taskMapper.selectById(7L).getStatus());
    }

    @Test
    void replayBookkeepingFailureAfterDispatchKeepsActiveKey() {
        FailedAnalysisTask task = failedTask(8L, "FAILED");
        when(taskMapper.selectById(8L)).thenReturn(task);
        when(taskMapper.updateById(any(FailedAnalysisTask.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.replay(8L));
        // The message is already out: the idempotency key must be kept, or the
        // bookkeeping failure would invite a duplicate replay.
        verify(redisTemplate, never()).delete(activeKey());
        verify(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));
    }

    private static String activeKey() {
        return com.example.server.utils.AnalysisTaskKeys.active("media-7",
                com.example.server.utils.AnalysisTaskKeys.goalDigest(GOAL, null));
    }

    private static String attemptsKey() {
        return com.example.server.utils.AnalysisTaskKeys.attempts("media-7",
                com.example.server.utils.AnalysisTaskKeys.goalDigest(GOAL, null));
    }
}
