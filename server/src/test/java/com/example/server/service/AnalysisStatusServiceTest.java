package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Priority of the derived status: result checkpoint > active task > terminal
 *  failure stage > not started. */
class AnalysisStatusServiceTest {

    private static final Long MEDIA_ID = 7L;
    private static final String GOAL = "总结视频要点";

    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final AnalysisDispatchService dispatchService = mock(AnalysisDispatchService.class);
    private final AnalysisStatusService service =
            new AnalysisStatusService(checkpointService, dispatchService);

    private AgentState completedState() {
        return new AgentState(GOAL,
                new AgentState.AgentPlan("plan", List.of()),
                new AnalysisResult("标题", List.of("结论"), List.of(), List.of(), List.of()),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()),
                1);
    }

    @Test
    void resultCheckpointWinsEvenWhileTaskIsActive() {
        when(checkpointService.loadResult(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(completedState());
        when(dispatchService.isActive(MEDIA_ID, GOAL, AnalysisMode.GENERAL)).thenReturn(true);

        TaskStatus status = service.current(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertEquals(TaskStatus.State.COMPLETED, status.state());
    }

    @Test
    void activeWithoutStageIsQueued() {
        when(dispatchService.isActive(MEDIA_ID, GOAL, AnalysisMode.GENERAL)).thenReturn(true);

        TaskStatus status = service.current(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertEquals(TaskStatus.State.QUEUED, status.state());
        assertEquals("任务已排队", status.message());
    }

    @Test
    void activeWithStageIsProcessingWithStageMessage() {
        when(dispatchService.isActive(MEDIA_ID, GOAL, AnalysisMode.GENERAL)).thenReturn(true);
        when(checkpointService.loadStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(TaskStage.VIDEO_CONTEXT);

        TaskStatus status = service.current(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertEquals(TaskStatus.State.PROCESSING, status.state());
        assertEquals("正在解析视频语音和关键画面", status.message());
    }

    @Test
    void retryingStageReportsProcessing() {
        when(dispatchService.isActive(MEDIA_ID, GOAL, AnalysisMode.GENERAL)).thenReturn(true);
        when(checkpointService.loadStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(TaskStage.RETRYING);

        TaskStatus status = service.current(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertEquals(TaskStatus.State.PROCESSING, status.state());
        assertEquals("任务执行异常，正在自动重试", status.message());
    }

    @Test
    void budgetExhaustedWithoutActiveTaskIsFailed() {
        when(checkpointService.loadStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(TaskStage.BUDGET_EXHAUSTED);

        TaskStatus status = service.current(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertEquals(TaskStatus.State.FAILED, status.state());
    }

    @Test
    void deadLetteredTaskIsFailed() {
        when(checkpointService.loadStage(MEDIA_ID, GOAL, AnalysisMode.GENERAL))
                .thenReturn(TaskStage.DEAD_LETTERED);

        TaskStatus status = service.current(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertEquals(TaskStatus.State.FAILED, status.state());
    }

    @Test
    void unknownTaskIsNotStarted() {
        TaskStatus status = service.current(MEDIA_ID, GOAL, AnalysisMode.GENERAL);

        assertEquals(TaskStatus.State.NOT_STARTED, status.state());
    }
}
