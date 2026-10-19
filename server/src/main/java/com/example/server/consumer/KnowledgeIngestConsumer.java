package com.example.server.consumer;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.entity.KnowledgeIngestJob;
import com.example.server.mapper.KnowledgeIngestJobMapper;
import com.example.server.service.KnowledgeIngestPipeline;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RocketMQMessageListener(topic = "${knowledge.ingest.topic:knowledge-ingest}",
        consumerGroup = "${knowledge.ingest.consumer-group:knowledge-ingest-workers}", consumeThreadNumber = 2)
public class KnowledgeIngestConsumer implements RocketMQListener<Long> {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestConsumer.class);
    private final KnowledgeIngestJobMapper jobs;
    private final KnowledgeIngestPipeline pipeline;
    private final RedissonClient locks;
    private final com.example.server.mapper.KnowledgeSourceMapper sources;
    private final ConcurrentHashMap<Long, Integer> running = new ConcurrentHashMap<>();

    public KnowledgeIngestConsumer(KnowledgeIngestJobMapper jobs, KnowledgeIngestPipeline pipeline,
                                   RedissonClient locks, com.example.server.mapper.KnowledgeSourceMapper sources) {
        this.jobs = jobs; this.pipeline = pipeline; this.locks = locks; this.sources = sources;
    }

    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        running.forEach((id, attempt) -> {
            try { jobs.heartbeat(id, attempt); }
            catch (RuntimeException e) { log.warn("knowledge_ingest_heartbeat_failed jobId={}", id, e); }
        });
    }

    @Override
    public void onMessage(Long id) {
        var lock = locks.getLock("knowledge:ingest-job:" + id);
        if (!lock.tryLock()) return; // Duplicate delivery; durable row remains owned by the active worker.
        try {
            if (jobs.start(id) == 0) return;
            KnowledgeIngestJob job = jobs.selectById(id);
            int attempt = job.getAttemptCount();
            running.put(id, attempt);
            try {
                var source = sources.selectById(job.getSourceId());
                if (source == null || "DELETED".equals(source.getStatus())) {
                    update(job, "CANCELLED", "CANCELLED", null);
                    return;
                }
                if ("READY".equals(source.getStatus()) && !Boolean.TRUE.equals(job.getForceRebuild())) {
                    // Transcript import or a previous attempt already published this source.
                    update(job, "READY", "READY", null);
                    return;
                }
                pipeline.ingest(job.getMediaId(), () -> update(job, "PROCESSING", "INDEXING", null));
                update(job, "READY", "READY", null);
            } catch (RuntimeException e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                update(job, attempt >= 3 ? "FAILED" : "QUEUED", "FAILED",
                        reason.substring(0, Math.min(1000, reason.length())));
                log.warn("knowledge_ingest_failed jobId={} attempt={}", id, attempt, e);
            } finally { running.remove(id); }
        } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private void update(KnowledgeIngestJob job, String state, String stage, String error) {
        jobs.update(null, new UpdateWrapper<KnowledgeIngestJob>().eq("id", job.getId())
                .eq("state", "PROCESSING").eq("attempt_count", job.getAttemptCount())
                .set("state", state).set("stage", stage).set("error_message", error)
                .setSql("next_dispatch_at = TIMESTAMPADD(SECOND, 30, CURRENT_TIMESTAMP(3))"));
    }
}
