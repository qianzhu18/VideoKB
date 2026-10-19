package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.entity.KnowledgeIngestJob;
import com.example.server.entity.KnowledgeSource;
import com.example.server.mapper.KnowledgeIngestJobMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeIngestJobService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestJobService.class);
    private final KnowledgeIngestJobMapper jobs;
    private final RocketMQTemplate queue;
    private final String topic;
    @Value("${knowledge.ingest.dispatch-enabled:true}")
    private boolean dispatchEnabled = true;

    public KnowledgeIngestJobService(KnowledgeIngestJobMapper jobs, RocketMQTemplate queue,
            @Value("${knowledge.ingest.topic:knowledge-ingest}") String topic) {
        this.jobs = jobs; this.queue = queue; this.topic = topic;
    }

    /** Called in the source-creation transaction: a successful upload cannot lose its job. */
    @Transactional
    public boolean enqueue(KnowledgeSource source) {
        if (source.getMediaId() == null || !("PENDING".equals(source.getStatus()) || "FAILED".equals(source.getStatus()))) return false;
        return jobs.enqueue(source.getOwnerUserId(), source.getId(), source.getMediaId()) > 0;
    }

    @Scheduled(fixedDelayString = "${knowledge.ingest.dispatch-interval-ms:10000}")
    public void dispatchOutbox() {
        if (!dispatchEnabled) return; // Read/evaluation runtimes must not process copied pending jobs.
        jobs.recoverInterrupted();
        for (KnowledgeIngestJob job : jobs.dispatchable()) {
            if (jobs.reserveDispatch(job.getId()) == 0) continue;
            try { queue.syncSend(topic, job.getId(), 2000); }
            catch (RuntimeException e) {
                jobs.update(null, new UpdateWrapper<KnowledgeIngestJob>().eq("id", job.getId()).eq("state", "QUEUED")
                        .set("error_message", "消息投递失败，等待自动重试")
                        .setSql("next_dispatch_at = TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP(3))"));
                log.warn("knowledge_ingest_dispatch_failed jobId={}", job.getId(), e);
            }
        }
    }

    public List<KnowledgeIngestJob> list(Long userId) {
        return jobs.selectList(new QueryWrapper<KnowledgeIngestJob>().eq("owner_user_id", userId)
                .orderByDesc("updated_at").last("LIMIT 500"));
    }

    public void cancel(Long sourceId) {
        jobs.update(null, new UpdateWrapper<KnowledgeIngestJob>().eq("source_id", sourceId)
                .set("state", "CANCELLED").set("stage", "CANCELLED"));
    }

    public boolean retry(Long userId, Long sourceId) {
        return jobs.update(null, new UpdateWrapper<KnowledgeIngestJob>().eq("owner_user_id", userId)
                .eq("source_id", sourceId).in("state", "FAILED", "READY")
                .set("state", "QUEUED").set("stage", "QUEUED").set("attempt_count", 0)
                .set("force_rebuild", true)
                .set("error_message", null).setSql("next_dispatch_at = CURRENT_TIMESTAMP(3)")) > 0;
    }
}
