package com.example.server.service.task;

import com.example.server.dto.TaskStatus;
import com.example.server.service.task.AnalysisTaskStateMachine.Event;
import com.example.server.service.task.AnalysisTaskStateMachine.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Full transition-table verification: every legal transition asserted, every
 * illegal one rejected. This table is the contract of the task lifecycle —
 * any wiring change has to pass through here first.
 */
class AnalysisTaskStateMachineTest {

    @Test
    void notStartedAcceptsSubmitConsumeStartAndRelease() {
        assertEquals(TaskStatus.State.QUEUED,
                AnalysisTaskStateMachine.next(TaskStatus.State.NOT_STARTED, Event.SUBMIT));
        assertEquals(TaskStatus.State.PROCESSING,
                AnalysisTaskStateMachine.next(TaskStatus.State.NOT_STARTED, Event.CONSUME_START));
        assertEquals(TaskStatus.State.FAILED,
                AnalysisTaskStateMachine.next(TaskStatus.State.NOT_STARTED, Event.RELEASE));
    }

    @Test
    void queuedStartsProcessingOrIsReleased() {
        assertEquals(TaskStatus.State.PROCESSING,
                AnalysisTaskStateMachine.next(TaskStatus.State.QUEUED, Event.CONSUME_START));
        assertEquals(TaskStatus.State.FAILED,
                AnalysisTaskStateMachine.next(TaskStatus.State.QUEUED, Event.RELEASE));
    }

    @Test
    void processingCompletesRetriesOrFails() {
        assertEquals(TaskStatus.State.COMPLETED,
                AnalysisTaskStateMachine.next(TaskStatus.State.PROCESSING, Event.COMPLETE));
        assertEquals(TaskStatus.State.QUEUED,
                AnalysisTaskStateMachine.next(TaskStatus.State.PROCESSING, Event.RETRY_SCHEDULED));
        assertEquals(TaskStatus.State.FAILED,
                AnalysisTaskStateMachine.next(TaskStatus.State.PROCESSING, Event.FAIL));
    }

    @Test
    void consumeStartFromProcessingIsIdempotentRedelivery() {
        // A redelivery after a crash re-enters consumption: the PROCESSING
        // self-loop is the normal recovery path.
        assertEquals(TaskStatus.State.PROCESSING,
                AnalysisTaskStateMachine.next(TaskStatus.State.PROCESSING, Event.CONSUME_START));
    }

    @Test
    void completedAbsorbsRedeliveryWithoutLeavingTerminalState() {
        // Under at-least-once semantics a leftover message of a completed task
        // re-triggers CONSUME_START: the terminal state absorbs the event as a
        // self-loop so the manifest never flickers COMPLETED -> PROCESSING
        // because of one idempotent redelivery.
        assertEquals(TaskStatus.State.COMPLETED,
                AnalysisTaskStateMachine.next(TaskStatus.State.COMPLETED, Event.CONSUME_START));
    }

    @Test
    void completedReopensOnlyThroughSubmitAndToleratesDuplicateComplete() {
        assertEquals(TaskStatus.State.QUEUED,
                AnalysisTaskStateMachine.next(TaskStatus.State.COMPLETED, Event.SUBMIT));
        assertEquals(TaskStatus.State.COMPLETED,
                AnalysisTaskStateMachine.next(TaskStatus.State.COMPLETED, Event.COMPLETE));
    }

    @Test
    void failedRevivesThroughSubmitOrRequeue() {
        assertEquals(TaskStatus.State.QUEUED,
                AnalysisTaskStateMachine.next(TaskStatus.State.FAILED, Event.SUBMIT));
        assertEquals(TaskStatus.State.QUEUED,
                AnalysisTaskStateMachine.next(TaskStatus.State.FAILED, Event.REQUEUE));
    }

    @Test
    void rejectsSkippedAndBackwardTransitions() {
        // Completing or failing before consumption ever started is illegal.
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.NOT_STARTED, Event.COMPLETE));
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.QUEUED, Event.COMPLETE));
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.QUEUED, Event.RETRY_SCHEDULED));
        // Terminal states must not be rewound by replay/retry events, and a
        // failed task cannot jump to completed.
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.COMPLETED, Event.REQUEUE));
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.COMPLETED, Event.RETRY_SCHEDULED));
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.FAILED, Event.COMPLETE));
        // NOT_STARTED has no retry semantics — nothing has been delivered yet.
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.NOT_STARTED, Event.RETRY_SCHEDULED));
    }

    @Test
    void rejectsMissingStateOrEvent() {
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(null, Event.SUBMIT));
        assertThrows(IllegalTransitionException.class, () ->
                AnalysisTaskStateMachine.next(TaskStatus.State.QUEUED, null));
    }
}
