package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.task.AnalysisTaskService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AnalysisDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchService.class);
    private static final int USER_REQUESTS_PER_MINUTE = 5;
    private static final int GLOBAL_REQUESTS_PER_MINUTE = 30;
    /** Batch entries (multi-upload, catch-up) share a wider budget: consumer
     *  concurrency is the real throttle for bulk work, and the interactive
     *  per-minute budget must stay reserved for humans. */
    private static final int USER_BULK_PER_MINUTE = 60;
    private static final int GLOBAL_BULK_PER_MINUTE = 120;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final AiService aiService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedissonClient redissonClient;
    private final TaskEventService taskEventService;
    private final AnalysisTaskService taskLedger;
    private final String analysisTopic;

    public AnalysisDispatchService(AiService aiService,
                                   MediaService mediaService,
                                   StringRedisTemplate redisTemplate,
                                   RocketMQTemplate rocketMQTemplate,
                                   RedissonClient redissonClient,
                                   TaskEventService taskEventService,
                                   AnalysisTaskService taskLedger,
                                   @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                   String analysisTopic) {
        this.aiService = aiService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redissonClient = redissonClient;
        this.taskEventService = taskEventService;
        this.taskLedger = taskLedger;
        this.analysisTopic = analysisTopic;
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 提交。 */
    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision) {
        return submit(mediaFile, goal, revision, AnalysisMode.GENERAL);
    }

    /** Batch-entry dispatch (multi-upload, ingest scans, space catch-up): same
     *  pipeline, wider limiter — consumer concurrency (4) is the real throttle for
     *  bulk work, and the interactive 5/min budget stays reserved for humans. */
    public SubmissionResult submitBulk(MediaFile mediaFile, String goal, AnalysisMode mode) {
        return submit(mediaFile, goal, null, mode, true);
    }

    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision, AnalysisMode mode) {
        return submit(mediaFile, goal, revision, mode, false);
    }

    private SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision,
                                    AnalysisMode mode, boolean bulk) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        Long mediaId = mediaFile.getId();
        String action = revision == null
                ? AnalysisTaskMsg.START_ANALYSIS
                : AnalysisTaskMsg.REVISE_ANALYSIS;
        String contentHash = revision == null ? contentHash(mediaId) : "media-" + mediaId;
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, resolvedMode);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        if (!acquireActiveKey(mediaId, activeKey)) return SubmissionResult.DUPLICATE;

        try {
            if (!tryAcquireQuota(mediaFile.getUserId(), bulk)) {
                redisTemplate.delete(activeKey);
                return SubmissionResult.RATE_LIMITED;
            }
            // 旧结果先留着。消费者真正接手后再切 Checkpoint，MQ 投递失败时用户还有结果可看。
            if (revision != null) aiService.stageRevision(revision, resolvedMode);
            rocketMQTemplate.convertAndSend(
                    analysisTopic,
                    new AnalysisTaskMsg(mediaId, action, contentHash, goal, resolvedMode.name()));
        } catch (RuntimeException e) {
            redisTemplate.delete(activeKey);
            if (revision != null) aiService.cancelStagedRevision(mediaId, goal, resolvedMode);
            log.error("analysis_dispatch_failed mediaId={} userId={}", mediaId, mediaFile.getUserId(), e);
            return SubmissionResult.FAILED;
        }

        try {
            taskEventService.publishAnalysis(mediaId, goal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.QUEUED, "任务已进入异步分析队列"), TaskStage.QUEUED);
        } catch (RuntimeException eventError) {
            // MQ 已经接单，通知失败不能把任务伪装成投递失败。
            log.warn("analysis_queued_event_failed mediaId={} userId={}",
                    mediaId, mediaFile.getUserId(), eventError);
        }
        // Ledger write happens after the broker accepted the message: only an
        // accepted task is worth recording (including the revision reopen
        // COMPLETED -> QUEUED).
        taskLedger.onSubmitted(mediaId, mediaFile.getUserId(), contentHash, goal, resolvedMode);
        return SubmissionResult.ACCEPTED;
    }

    public boolean isActive(Long mediaId, String goal) {
        return isActive(mediaId, goal, AnalysisMode.GENERAL);
    }

    /** setIfAbsent on the active marker, plus one ledger-backed reconcile: the marker
     *  is best-effort state that a killed process leaves behind for up to {@code ACTIVE_TTL},
     *  and it is keyed by content hash, so one user's marker can even shadow another user's
     *  upload of the same video. The ledger is the authority on whether this media's task is
     *  actually in flight — a duplicate marker with no in-flight ledger row is stale, so it
     *  is cleared and the submit retried once instead of dead-locking the content behind a
     *  ghost. Concurrent same-content analysis stays serialized by the consumer's Redisson
     *  lock and the content-level context lock, so clearing a stale marker cannot double-burn
     *  transcription. */
    private boolean acquireActiveKey(Long mediaId, String activeKey) {
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(mediaId), ACTIVE_TTL))) {
            return true;
        }
        if (taskLedger.hasActiveTask(mediaId)
                || !Boolean.TRUE.equals(redisTemplate.delete(activeKey))) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(mediaId), ACTIVE_TTL));
    }

    public boolean isActive(Long mediaId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        return Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active(contentHash(mediaId), goalDigest)))
                || Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active("media-" + mediaId, goalDigest)));
    }

    /**
     * 追问和证据检索同样会触发模型调用，统一复用分析配额，避免绕过成本护栏。
     */
    public void requireAiQuota(Long userId) {
        try {
            if (!tryAcquireQuota(userId)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("ai_rate_limiter_unavailable userId={}", userId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 服务限流器暂不可用，请稍后再试");
        }
    }

    private boolean tryAcquireQuota(Long userId) {
        return tryAcquireQuota(userId, false);
    }

    private boolean tryAcquireQuota(Long userId, boolean bulk) {
        String scope = bulk ? "bulk" : "user";
        int perMinute = bulk ? USER_BULK_PER_MINUTE : USER_REQUESTS_PER_MINUTE;
        RRateLimiter userLimiter = redissonClient.getRateLimiter("limit:ai:" + scope + ":" + userId);
        userLimiter.trySetRate(RateType.OVERALL, perMinute, 1, RateIntervalUnit.MINUTES);
        if (!userLimiter.tryAcquire()) return false;

        RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:" + scope + ":global");
        globalLimiter.trySetRate(
                RateType.OVERALL, bulk ? GLOBAL_BULK_PER_MINUTE : GLOBAL_REQUESTS_PER_MINUTE,
                1, RateIntervalUnit.MINUTES);
        return globalLimiter.tryAcquire();
    }

    private String contentHash(Long mediaId) {
        return AnalysisTaskKeys.normalizeContentHash(
                mediaId, mediaService.contentHash(mediaId));
    }

    public enum SubmissionResult {
        ACCEPTED,
        RATE_LIMITED,
        DUPLICATE,
        FAILED
    }
}
