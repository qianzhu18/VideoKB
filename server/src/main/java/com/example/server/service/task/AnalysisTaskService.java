package com.example.server.service.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.AnalysisTask;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.AnalysisTaskMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Durable ledger of analysis tasks: every state change is validated by
 * {@link AnalysisTaskStateMachine} before it is persisted.
 *
 * <p>The ledger is a <strong>reconcilable source of truth</strong>, not a new
 * runtime authority — deduplication and mutual exclusion remain owned by the
 * Redis idempotency keys. All writes are therefore best-effort: a database
 * outage or an illegal transition is logged as a warning and never propagated
 * to callers (submit endpoint, consumer, replay endpoint), so a ledger problem
 * can never escalate into an analysis-pipeline outage.
 *
 * <p>The unique key is (media_id, goal_digest) — the same task identity used
 * by checkpoints and Redis keys. Redeliveries, retries and replays of one task
 * all converge on a single row, which is what makes the manifest view a valid
 * audit trail for "a new batch must not re-run already-succeeded videos".
 */
@Service
public class AnalysisTaskService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskService.class);
    private static final int MANIFEST_LIMIT = 200;
    private static final int ERROR_TYPE_MAX = 128;
    private static final int ERROR_MESSAGE_MAX = 1_000;

    private final AnalysisTaskMapper taskMapper;
    private final MediaFileMapper mediaFileMapper;

    public AnalysisTaskService(AnalysisTaskMapper taskMapper, MediaFileMapper mediaFileMapper) {
        this.taskMapper = taskMapper;
        this.mediaFileMapper = mediaFileMapper;
    }

    /** Dispatch accepted a task: NOT_STARTED/FAILED/COMPLETED(revision reopen) --SUBMIT--> QUEUED. */
    public void onSubmitted(Long mediaId, Long userId, String contentHash, String goal, AnalysisMode mode) {
        apply(mediaId, userId, contentHash, goal, mode, AnalysisTaskStateMachine.Event.SUBMIT, row -> {
            row.setAttemptCount(0);
            row.setLastStage("QUEUED");
            row.setErrorType(null);
            row.setErrorMessage(null);
        });
    }

    /** The consumer started processing; {@code attempt} is this task's delivery number. */
    public void onStarted(Long mediaId, Long userId, String contentHash, String goal, AnalysisMode mode, int attempt) {
        apply(mediaId, userId, contentHash, goal, mode, AnalysisTaskStateMachine.Event.CONSUME_START, row -> {
            row.setAttemptCount(attempt);
            row.setLastStage("CONSUMING");
        });
    }

    /** A consume attempt failed before the delivery cap; the broker will redeliver. */
    public void onRetryScheduled(Long mediaId, String contentHash, String goal, AnalysisMode mode, int attempt) {
        apply(mediaId, null, contentHash, goal, mode, AnalysisTaskStateMachine.Event.RETRY_SCHEDULED, row -> {
            row.setAttemptCount(attempt);
            row.setLastStage("RETRYING");
        });
    }

    /** Analysis succeeded or a content-level reuse hit; {@code lastStage} distinguishes
     *  COMPLETED from COMPLETED_REUSED. */
    public void onCompleted(Long mediaId, String contentHash, String goal, AnalysisMode mode, String lastStage) {
        apply(mediaId, null, contentHash, goal, mode, AnalysisTaskStateMachine.Event.COMPLETE, row -> {
            row.setLastStage(lastStage);
            row.setErrorType(null);
            row.setErrorMessage(null);
        });
    }

    /** Permanent failure / exhausted deliveries / burnt budget; the task failed terminally. */
    public void onFailed(Long mediaId,
                         String contentHash,
                         String goal,
                         AnalysisMode mode,
                         String lastStage,
                         String errorType,
                         String errorMessage) {
        apply(mediaId, null, contentHash, goal, mode, AnalysisTaskStateMachine.Event.FAIL, row -> {
            row.setLastStage(lastStage);
            row.setErrorType(truncate(errorType, ERROR_TYPE_MAX));
            row.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX));
        });
    }

    /** Administrator replayed the task from the failure ledger: FAILED --REQUEUE--> QUEUED. */
    public void onRequeued(Long mediaId, String contentHash, String goal, AnalysisMode mode) {
        apply(mediaId, null, contentHash, goal, mode, AnalysisTaskStateMachine.Event.REQUEUE, row -> {
            row.setAttemptCount(0);
            row.setLastStage("MANUAL_REPLAY");
            row.setErrorType(null);
            row.setErrorMessage(null);
        });
    }

    /** Poison-message convergence: the message is invalid and the task is voided. */
    public void onReleased(Long mediaId, String contentHash, String goal, AnalysisMode mode) {
        apply(mediaId, null, contentHash, goal, mode, AnalysisTaskStateMachine.Event.RELEASE, row -> {
            row.setLastStage("DEAD_LETTERED");
            row.setErrorType("InvalidMessage");
            row.setErrorMessage("任务消息结构非法，已作废");
        });
    }

    /** Task manifest of one user: the audit view over batch processing state. */
    public List<AnalysisTaskView> manifest(Long userId) {
        List<AnalysisTask> rows = taskMapper.selectList(new QueryWrapper<AnalysisTask>()
                .eq("owner_user_id", userId)
                .orderByDesc("updated_at")
                .last("LIMIT " + MANIFEST_LIMIT));
        if (rows.isEmpty()) return List.of();
        Map<Long, String> filenames = mediaFileMapper.selectBatchIds(
                        rows.stream().map(AnalysisTask::getMediaId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(MediaFile::getId, MediaFile::getFilename, (a, b) -> a));
        return rows.stream()
                .map(row -> AnalysisTaskView.from(row, filenames.get(row.getMediaId())))
                .toList();
    }

    /** Latest ledger row per media id — lets the catch-up sweep tell never-dispatched
     *  sources apart from failed ones (which recover through reindex, not re-dispatch). */
    public Map<Long, AnalysisTask> latestByMediaIds(Collection<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) return Map.of();
        List<AnalysisTask> rows = taskMapper.selectList(new QueryWrapper<AnalysisTask>()
                .in("media_id", mediaIds)
                .orderByAsc("id"));
        Map<Long, AnalysisTask> latest = new LinkedHashMap<>();
        for (AnalysisTask row : rows) {
            latest.put(row.getMediaId(), row);
        }
        return latest;
    }

    /** Whether the media currently has a ledger row in QUEUED/PROCESSING — the
     *  reconcilable authority for "is this task actually in flight", unlike the
     *  best-effort Redis active marker which a killed process can leave behind. */
    public boolean hasActiveTask(Long mediaId) {
        if (mediaId == null) return false;
        AnalysisTask row = latestByMediaIds(List.of(mediaId)).get(mediaId);
        return row != null && ("QUEUED".equals(row.getState()) || "PROCESSING".equals(row.getState()));
    }

    private void apply(Long mediaId,
                       Long userIdHint,
                       String contentHash,
                       String goal,
                       AnalysisMode mode,
                       AnalysisTaskStateMachine.Event event,
                       Consumer<AnalysisTask> mutator) {
        try {
            if (mediaId == null || goal == null || goal.isBlank()) {
                log.warn("analysis_task_ledger_skipped mediaId={} event={} reason=missing identity",
                        mediaId, event);
                return;
            }
            AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
            String normalizedHash = AnalysisTaskKeys.normalizeContentHash(mediaId, contentHash);
            String goalDigest = AnalysisTaskKeys.goalDigest(goal, resolvedMode);
            AnalysisTask row = taskMapper.selectOne(new QueryWrapper<AnalysisTask>()
                    .eq("media_id", mediaId)
                    .eq("goal_digest", goalDigest));
            TaskStatus.State from = row == null ? TaskStatus.State.NOT_STARTED : parseState(row.getState());
            TaskStatus.State target = AnalysisTaskStateMachine.next(from, event);
            if (row == null) {
                row = new AnalysisTask();
                row.setMediaId(mediaId);
                row.setGoalDigest(goalDigest);
                row.setOwnerUserId(resolveOwner(mediaId, userIdHint));
                row.setMode(resolvedMode.name());
                row.setCreatedAt(LocalDateTime.now());
            }
            row.setContentHash(normalizedHash);
            row.setState(target.name());
            row.setUpdatedAt(LocalDateTime.now());
            mutator.accept(row);
            if (row.getId() == null) {
                try {
                    taskMapper.insert(row);
                } catch (RuntimeException e) {
                    if (!isDuplicateKey(e)) throw e;
                    // Race: the submit path inserts the row right as local-queue
                    // consumption starts (delivery is millisecond-fast here), so the
                    // SELECT above missed it and the INSERT hit the unique key. Losing
                    // this write would strand the ledger (e.g. stuck in QUEUED, then
                    // COMPLETE is rejected as an illegal transition). Re-read and
                    // replay the event on the winning row instead.
                    AnalysisTask winner = taskMapper.selectOne(new QueryWrapper<AnalysisTask>()
                            .eq("media_id", mediaId)
                            .eq("goal_digest", goalDigest));
                    if (winner == null) throw e;
                    TaskStatus.State racedFrom = parseState(winner.getState());
                    winner.setContentHash(normalizedHash);
                    winner.setState(AnalysisTaskStateMachine.next(racedFrom, event).name());
                    winner.setUpdatedAt(LocalDateTime.now());
                    mutator.accept(winner);
                    taskMapper.updateById(winner);
                }
            } else {
                taskMapper.updateById(row);
            }
        } catch (AnalysisTaskStateMachine.IllegalTransitionException e) {
            // An illegal transition means some path bypassed the expected order:
            // keep the previous state and log loudly so the wiring bug is findable.
            log.warn("analysis_task_illegal_transition mediaId={} event={}", mediaId, event, e);
        } catch (RuntimeException e) {
            log.warn("analysis_task_ledger_write_failed mediaId={} event={}", mediaId, event, e);
        }
    }

    private static boolean isDuplicateKey(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.contains("Duplicate entry");
    }

    private Long resolveOwner(Long mediaId, Long userIdHint) {
        if (userIdHint != null) return userIdHint;
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            throw new IllegalStateException("media not found for task ledger: " + mediaId);
        }
        return media.getUserId();
    }

    private static TaskStatus.State parseState(String value) {
        try {
            return TaskStatus.State.valueOf(value);
        } catch (IllegalArgumentException e) {
            // Corrupt ledger data must not wedge the task: degrade to NOT_STARTED
            // and let the state machine converge again.
            return TaskStatus.State.NOT_STARTED;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** One manifest row: task identity, current state, delivery count and latest stage. */
    public record AnalysisTaskView(Long taskId,
                                   Long mediaId,
                                   String filename,
                                   String contentHash,
                                   String mode,
                                   String state,
                                   Integer attemptCount,
                                   String lastStage,
                                   String errorType,
                                   LocalDateTime updatedAt) {

        static AnalysisTaskView from(AnalysisTask row, String filename) {
            return new AnalysisTaskView(
                    row.getId(),
                    row.getMediaId(),
                    filename == null ? "(媒体已删除)" : filename,
                    row.getContentHash(),
                    row.getMode(),
                    row.getState(),
                    row.getAttemptCount(),
                    row.getLastStage(),
                    row.getErrorType(),
                    row.getUpdatedAt());
        }
    }
}
