package com.example.server.service.task;

import com.example.server.dto.TaskStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Explicit state machine for analysis tasks: the single authority on legal
 * lifecycle transitions.
 *
 * <p>Before this class existed, task state was derived ad hoc from Redis
 * idempotency keys and checkpoints, and every site could overwrite it freely.
 * Illegal transitions (e.g. re-queuing a completed task) were neither detected
 * nor expressible in tests. This machine consolidates the rules into one table;
 * {@link AnalysisTaskService} consults it before every ledger write: legal
 * transitions are persisted, illegal ones are rejected and logged as warnings.
 *
 * <p>States reuse {@link TaskStatus.State} (the five user-visible states).
 * Events are named after their trigger:
 * <ul>
 *   <li>{@code SUBMIT} — {@code AnalysisDispatchService} accepted a task
 *       (including revision reopen and user resubmission after failure).</li>
 *   <li>{@code CONSUME_START} — the consumer began processing a delivery.
 *       Allowed from QUEUED/PROCESSING/COMPLETED: redeliveries and leftover
 *       messages of already-completed tasks re-enter consumption as a normal
 *       idempotent path, not an illegal transition.</li>
 *   <li>{@code RETRY_SCHEDULED} — a consume attempt failed before the delivery
 *       cap; the message goes back to the broker for a delayed retry.</li>
 *   <li>{@code COMPLETE} — analysis succeeded or a content-level reuse hit
 *       (the COMPLETED self-loop is the normal convergence of idempotent
 *       redelivery).</li>
 *   <li>{@code FAIL} — permanent failure, exhausted deliveries or a burnt
 *       budget; the task enters a terminal state.</li>
 *   <li>{@code REQUEUE} — an administrator replayed the task from the failure
 *       ledger.</li>
 *   <li>{@code RELEASE} — poison-message convergence: the message itself is
 *       structurally invalid and the task is voided outright.</li>
 * </ul>
 *
 * <p>This is a pure component: no state, no Spring dependencies. Its entire
 * behavior is locked down by {@code AnalysisTaskStateMachineTest} using
 * fault-injection style assertions.
 */
public final class AnalysisTaskStateMachine {

    /** Raised on an illegal transition; carries the from-state and event for logs. */
    public static final class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(TaskStatus.State from, Event event, String reason) {
            super("illegal analysis task transition: " + from + " --" + event + "--> (" + reason + ")");
        }
    }

    public enum Event {
        SUBMIT,
        CONSUME_START,
        RETRY_SCHEDULED,
        COMPLETE,
        FAIL,
        REQUEUE,
        RELEASE
    }

    private static final Map<TaskStatus.State, Map<Event, TaskStatus.State>> TRANSITIONS =
            buildTransitions();

    private AnalysisTaskStateMachine() {
    }

    /** Returns the legal target state for the event applied to {@code from};
     *  throws {@link IllegalTransitionException} when the transition is not allowed. */
    public static TaskStatus.State next(TaskStatus.State from, Event event) {
        if (from == null || event == null) {
            throw new IllegalTransitionException(from, event, "state and event are required");
        }
        TaskStatus.State target = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (target == null) {
            throw new IllegalTransitionException(from, event, "transition not allowed from " + from);
        }
        return target;
    }

    /** Events allowed in the given state; used by tests and warning messages. */
    public static Set<Event> allowedEvents(TaskStatus.State from) {
        return TRANSITIONS.getOrDefault(from, Map.of()).keySet();
    }

    private static Map<TaskStatus.State, Map<Event, TaskStatus.State>> buildTransitions() {
        Map<TaskStatus.State, Map<Event, TaskStatus.State>> table = new EnumMap<>(TaskStatus.State.class);
        // NOT_STARTED: the task has not been accepted yet. CONSUME_START covers the
        // out-of-order case where a message arrives before the ledger row exists.
        table.put(TaskStatus.State.NOT_STARTED, Map.of(
                Event.SUBMIT, TaskStatus.State.QUEUED,
                Event.CONSUME_START, TaskStatus.State.PROCESSING,
                Event.RELEASE, TaskStatus.State.FAILED));
        // QUEUED: waiting for consumption. RELEASE voids a task whose message turns
        // out to be invalid before any processing happened.
        table.put(TaskStatus.State.QUEUED, Map.of(
                Event.CONSUME_START, TaskStatus.State.PROCESSING,
                Event.RELEASE, TaskStatus.State.FAILED));
        // PROCESSING: mid-consumption. The self-loop is crash recovery via
        // redelivery; a retryable failure falls back to QUEUED for the broker.
        table.put(TaskStatus.State.PROCESSING, Map.of(
                Event.CONSUME_START, TaskStatus.State.PROCESSING,
                Event.COMPLETE, TaskStatus.State.COMPLETED,
                Event.RETRY_SCHEDULED, TaskStatus.State.QUEUED,
                Event.FAIL, TaskStatus.State.FAILED));
        // COMPLETED: the terminal state absorbs idempotent redeliveries as
        // self-loops (CONSUME_START / COMPLETE of leftover messages); only an
        // explicit revision submit reopens the task.
        table.put(TaskStatus.State.COMPLETED, Map.of(
                Event.SUBMIT, TaskStatus.State.QUEUED,
                Event.CONSUME_START, TaskStatus.State.COMPLETED,
                Event.COMPLETE, TaskStatus.State.COMPLETED));
        // FAILED: user resubmission or an administrator replay can revive the task.
        table.put(TaskStatus.State.FAILED, Map.of(
                Event.SUBMIT, TaskStatus.State.QUEUED,
                Event.REQUEUE, TaskStatus.State.QUEUED));
        return table;
    }
}
