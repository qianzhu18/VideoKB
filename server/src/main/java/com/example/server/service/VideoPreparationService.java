package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.utils.AnalysisTaskKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Shared ASR/OCR preparation, independent of Agent reports and knowledge indexing. */
@Service
public class VideoPreparationService {
    private static final Logger log = LoggerFactory.getLogger(VideoPreparationService.class);
    private static final long CONTEXT_LOCK_WAIT_SECONDS = 300;
    private static final Duration CONTEXT_OWNER_TTL = Duration.ofDays(7);
    private final VideoContextService videoContextService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;

    public VideoPreparationService(VideoContextService videoContextService,
            AgentCheckpointService checkpointService, AgentTelemetry telemetry,
            MediaService mediaService, TaskEventService taskEventService,
            RedissonClient redissonClient, StringRedisTemplate redisTemplate) {
        this.videoContextService = videoContextService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
    }
    /**
     * 解析视频上下文（ASR + 关键帧 OCR）。
     *
     * <p>ASR/OCR 是只取决于视频内容的确定性预处理，与用户目标无关，因此按内容级（contentHash）
     * 复用：同一个视频换个分析目标、或被不同用户重复上传，都不该再烧一遍算力与第三方额度。
     * 复用顺序为：本 mediaId 检查点 → 内容级检查点 → 加内容锁后真正构建。
     */
    public VideoContext prepare(MediaFile mediaFile,
                                        String userGoal,
                                        String traceId,
                                        AnalysisMode mode) {
        VideoContext checkpoint = checkpointService.loadContext(mediaFile.getId());
        if (checkpoint != null) {
            telemetry.increment(traceId, "contextCheckpointHits", 1);
            return new VideoContext(checkpoint.source(), userGoal, checkpoint.segments());
        }

        String contentHash = AnalysisTaskKeys.normalizeContentHash(
                mediaFile.getId(), mediaService.contentHash(mediaFile.getId()));
        VideoContext reused = reuseContentContext(mediaFile, userGoal, traceId, contentHash);
        if (reused != null) return reused;

        // 同一视频被多个目标同时提交时，只让一个消费者真正跑 ASR/OCR，其余等待后复用。
        RLock contextLock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
        boolean locked = false;
        try {
            locked = contextLock.tryLock(CONTEXT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            // 无论是否抢到锁都要重查，且必须先查自身检查点：同一个 mediaId 换目标并发提交时，
            // 先完成者登记的归属正是这个 mediaId，只查归属索引会被 "owner == 自己" 判空而漏掉，
            // 于是又重跑一遍完整 ASR/OCR。
            VideoContext own = checkpointService.loadContext(mediaFile.getId());
            if (own != null) {
                telemetry.increment(traceId, "contextCheckpointHits", 1);
                return new VideoContext(own.source(), userGoal, own.segments());
            }
            VideoContext afterWait = reuseContentContext(mediaFile, userGoal, traceId, contentHash);
            if (afterWait != null) return afterWait;

            if (!locked) {
                // 没抢到锁说明同一视频的上下文仍在被另一个消费者构建。此处绝不能自己再跑一遍——
                // 那正是要消除的重复 ASR/OCR。本地等待只负责消化短时争用，超出等待窗口就交给
                // RocketMQ 做跨时间重投：等对方落盘后，重投的这条消息会在上面两次复用检查中直接命中。
                telemetry.increment(traceId, "contextLockContentions", 1);
                log.warn("context_build_in_progress mediaId={} contentHash={} waitedSeconds={}",
                        mediaFile.getId(), contentHash, CONTEXT_LOCK_WAIT_SECONDS);
                throw new IllegalStateException("同一视频的上下文正在构建中，稍后重试");
            }
            return buildContext(mediaFile, userGoal, traceId, contentHash, mode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待视频上下文构建锁被中断", e);
        } finally {
            if (locked && contextLock.isHeldByCurrentThread()) contextLock.unlock();
        }
    }

    /** 命中内容级上下文时，把它挂到当前 mediaId 上并改写素材地址，跳过整段 ASR/OCR。 */
    private VideoContext reuseContentContext(MediaFile mediaFile,
                                             String userGoal,
                                             String traceId,
                                             String contentHash) {
        Long ownerMediaId = contextOwner(contentHash);
        if (ownerMediaId == null || ownerMediaId.equals(mediaFile.getId())) return null;

        VideoContext ownerContext = checkpointService.loadContext(ownerMediaId);
        if (ownerContext == null) {
            // 归属记录过期或对应检查点已被清理，丢弃这条索引，走正常构建。
            redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
            return null;
        }

        VideoContext localized = reusableContext(mediaFile.getFilePath(), ownerContext);
        checkpointService.saveContext(mediaFile.getId(), localized);
        telemetry.increment(traceId, "contextContentReuses", 1);
        log.info("video_context_reused mediaId={} sourceMediaId={} contentHash={}",
                mediaFile.getId(), ownerMediaId, contentHash);
        return new VideoContext(localized.source(), userGoal, localized.segments());
    }

    private VideoContext buildContext(MediaFile mediaFile,
                                      String userGoal,
                                      String traceId,
                                      String contentHash,
                                      AnalysisMode mode) {
        taskEventService.publishAnalysis(mediaFile.getId(), userGoal, mode,
                TaskStatus.of(TaskStatus.State.PROCESSING, "正在并行提取语音与关键帧"),
                TaskStage.VIDEO_CONTEXT);
        long started = System.nanoTime();
        try {
            VideoContext context = videoContextService.build(mediaFile.getFilePath(), userGoal, traceId);
            try {
                checkpointService.saveContext(mediaFile.getId(), context);
            } catch (RuntimeException e) {
                videoContextService.deleteEvidenceFrames(context);
                throw e;
            }
            // 先落盘再登记归属：登记成功即意味着该上下文确实可被读取，避免别人拿到空索引。
            rememberContextOwner(contentHash, mediaFile.getId());
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, true);
            return context;
        } catch (RuntimeException e) {
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, false);
            throw e;
        }
    }

    private Long contextOwner(String contentHash) {
        try {
            String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.contextOwner(contentHash));
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
            return null;
        } catch (RuntimeException e) {
            // 复用只是省钱优化，Redis 故障时退回正常构建，不能因此让分析失败。
            log.warn("context_owner_read_failed contentHash={}", contentHash, e);
            return null;
        }
    }

    private void rememberContextOwner(String contentHash, Long mediaId) {
        try {
            redisTemplate.opsForValue().set(
                    AnalysisTaskKeys.contextOwner(contentHash),
                    String.valueOf(mediaId),
                    CONTEXT_OWNER_TTL);
        } catch (RuntimeException e) {
            log.warn("context_owner_write_failed contentHash={} mediaId={}", contentHash, mediaId, e);
        }
    }

    private VideoContext reusableContext(String targetSource, VideoContext sourceContext) {
        return new VideoContext(targetSource, "", sourceContext.segments().stream()
                .map(segment -> new VideoContext.VideoSegment(
                        segment.startMs(),
                        segment.endMs(),
                        segment.transcript(),
                        segment.ocrTexts(),
                        segment.evidenceFrames().isEmpty()
                                ? java.util.List.of()
                                : java.util.List.of(targetSource + "#timestampMs=" + segment.startMs())))
                .toList());
    }

}
