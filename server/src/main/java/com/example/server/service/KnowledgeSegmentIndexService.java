package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.KnowledgeSourceVersion;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceVersionMapper;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns an analyzed media into versioned knowledge assets: authoritative segment rows in
 * MySQL plus segment-level vectors with ownership payload in Qdrant. The version row is
 * the explicit status machine (PENDING → INDEXING → READY / FAILED); failures are never
 * silent so cross-video retrieval problems stay debuggable.
 */
@Service
public class KnowledgeSegmentIndexService {

    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";
    private static final int EMBEDDING_BATCH_SIZE = 32;
    /** Bumped when the segment derivation logic changes so stale rows can be located. */
    public static final String PARSER_VERSION = "ctx-segments-v1";

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSourceVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final QdrantVectorStore vectorStore;
    private final AgentCheckpointService checkpointService;
    private final VideoChunkingService chunkingService;
    private final EmbeddingUtils embeddingUtils;
    private final KnowledgeAuditService auditService;
    private final String embeddingModel;
    private final KnowledgeIndexPublisher publisher;
    private final KnowledgeMetrics metrics;
    private final org.redisson.api.RedissonClient locks;
    private final KnowledgeBlockIndexService blockIndex;
    private final String indexProfile;
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeLexicalIndexService lexicalIndex;

    public KnowledgeSegmentIndexService(KnowledgeSourceService sourceService,
                                        KnowledgeSourceVersionMapper versionMapper,
                                        KnowledgeSegmentMapper segmentMapper,
                                        QdrantVectorStore vectorStore,
                                        AgentCheckpointService checkpointService,
                                        VideoChunkingService chunkingService,
                                        EmbeddingUtils embeddingUtils,
                                        KnowledgeAuditService auditService,
                                        @Value("${ai.embedding.model:BAAI/bge-m3}") String embeddingModel, KnowledgeIndexPublisher publisher, org.redisson.api.RedissonClient locks, KnowledgeMetrics metrics,
                                        KnowledgeBlockIndexService blockIndex,
                                        @Value("${knowledge.index.profile:raw-boundary-v1-max1400-overlap1}") String indexProfile) {
        this.sourceService = sourceService;
        this.versionMapper = versionMapper;
        this.segmentMapper = segmentMapper;
        this.vectorStore = vectorStore;
        this.checkpointService = checkpointService;
        this.chunkingService = chunkingService;
        this.embeddingUtils = embeddingUtils;
        this.auditService = auditService;
        this.embeddingModel = embeddingModel;
        this.publisher = publisher;
        this.metrics = metrics;
        this.locks = locks;
        this.blockIndex = blockIndex;
        this.indexProfile = indexProfile;
        if (!"legacy-v1".equals(indexProfile) && !EvidenceBlockChunker.PROFILE.equals(indexProfile))
            throw new IllegalArgumentException("未知知识索引 profile");
    }

    /** Build a new generation; publish only after all embeddings and vector writes succeed. */
    public List<KnowledgeSegment> indexMedia(Long mediaId) {
        var lock = locks.getLock("knowledge:index:" + mediaId);
        boolean locked = false;
        try {
            locked = lock.tryLock(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) throw new IllegalStateException("知识索引正在构建，请稍后重试");
            return buildGeneration(mediaId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待索引锁被中断", e);
        } finally { if (locked && lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private List<KnowledgeSegment> buildGeneration(Long mediaId) {
        KnowledgeSource source = sourceService.requireSourceByMediaId(mediaId);
        KnowledgeSourceVersion latest = versionMapper.selectOne(new QueryWrapper<KnowledgeSourceVersion>()
                .eq("source_id", source.getId()).orderByDesc("version_no").last("LIMIT 1"));
        KnowledgeSourceVersion version = new KnowledgeSourceVersion();
        version.setSourceId(source.getId());
        version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        version.setContentHash(source.getContentHash());
        version.setStatus(STATUS_INDEXING);
        version.setParserVersion(PARSER_VERSION);
        version.setEmbeddingModel(embeddingModel);
        version.setIndexProfile(indexProfile);
        if (!"legacy-v1".equals(indexProfile)) version.setParserVersion("raw-evidence-v2");
        versionMapper.insert(version);
        try {
            List<KnowledgeSegment> segments = metrics.measure("ingest.chunking", () -> buildSegments(source, version));
            if (segments.isEmpty()) throw new IllegalStateException("解析上下文为空，无法生成知识分段");
            for (KnowledgeSegment segment : segments) segmentMapper.insert(segment);
            if ("legacy-v1".equals(indexProfile)) upsertVectors(source, version, segments);
            else blockIndex.index(source, version, segments);
            if (lexicalIndex != null && lexicalIndex.enabled())
                metrics.run("ingest.lexical_write", () -> lexicalIndex.index(source, version));
            metrics.run("ingest.publish", () -> publisher.publish(source, version));
            try {
            auditService.record(source.getOwnerUserId(), "SOURCE_INDEXED", "SOURCE", source.getId(),
                    source.getSpaceId(), source.getCollectionId(),
                    "segments=" + segments.size() + ";version=" + version.getVersionNo());
            } catch (RuntimeException ignored) { /* Publication is committed; audit failure cannot invalidate it. */ }
            return segments;
        } catch (RuntimeException e) {
            publisher.fail(source, version, abbreviate(e.getMessage(), 1000));
            throw e;
        }
    }

    /** Records an indexing failure for callers that must swallow the exception. */
    public void markIndexFailed(Long mediaId, String reason) {
        try {
            KnowledgeSource source = sourceService.requireSourceByMediaId(mediaId);
            // With index-before-report, an already-READY source means the transcript and
            // vectors are in place — a downstream agent-report failure (e.g. budget)
            // must not drag the searchable asset back to FAILED.
            if (STATUS_READY.equals(source.getStatus())) return;
            KnowledgeSourceVersion version = currentVersion(source);
            version.setStatus(STATUS_FAILED);
            version.setParserVersion(PARSER_VERSION);
            version.setEmbeddingModel(embeddingModel);
            version.setFailureReason(abbreviate(reason, 1000));
            versionMapper.updateById(version);
            sourceService.updateIndexStatus(source, KnowledgeSourceService.STATUS_FAILED);
        } catch (RuntimeException ignored) {
            // Source/version already missing: the failure record would have nothing to attach to.
        }
    }

    /** Ownership-checked entry point for manual rebuilds from the knowledge APIs. */
    public List<KnowledgeSegment> indexSource(Long userId, Long sourceId) {
        KnowledgeSource source = sourceService.requireOwnedSource(userId, sourceId);
        return indexMedia(source.getMediaId());
    }

    /**
     * Owner-checked read of the authoritative segment rows backing a media asset.
     * The MCP adapter cites evidence through this; it never touches the segment tables.
     */
    public List<KnowledgeSegment> listSegments(Long userId, Long mediaId) {
        return listSegments(userId,mediaId,null);
    }

    public List<KnowledgeSegment> listSegments(Long userId, Long mediaId, Long versionId) {
        KnowledgeSource source = sourceService.requireSourceByMediaId(mediaId);
        if (!userId.equals(source.getOwnerUserId())) {
            throw new SecurityException("无权访问该内容源");
        }
        if (!STATUS_READY.equals(source.getStatus())) return List.of();
        KnowledgeSourceVersion version = versionId == null ? currentVersion(source) : versionMapper.selectById(versionId);
        if (version == null || !source.getId().equals(version.getSourceId()) || !STATUS_READY.equals(version.getStatus())) {
            throw new BusinessException(com.example.server.common.ErrorCode.NOT_FOUND,"证据版本不存在或尚未发布");
        }
        return segmentMapper.selectList(new QueryWrapper<KnowledgeSegment>()
                .eq("media_id", mediaId).eq("version_id", version.getId())
                .orderByAsc("start_ms"));
    }

    private List<KnowledgeSegment> buildSegments(KnowledgeSource source, KnowledgeSourceVersion version) {
        if (!"legacy-v1".equals(indexProfile)) return buildOriginalEvidence(source, version);
        List<VideoChunk> chunks = checkpointService.loadChunks(source.getMediaId());
        if (chunks == null || chunks.isEmpty()) {
            VideoContext context = checkpointService.loadContext(source.getMediaId());
            chunks = chunkingService.build(context.segments());
        }
        List<KnowledgeSegment> segments = new ArrayList<>();
        for (VideoChunk chunk : chunks) {
            for (VideoContext.VideoSegment raw : chunk.rawSegments()) {
                if (raw.transcript().isBlank() && raw.ocrTexts().isEmpty()) continue;
                KnowledgeSegment segment = new KnowledgeSegment();
                segment.setId(UUID.randomUUID().toString());
                segment.setSourceId(source.getId());
                segment.setVersionId(version.getId());
                segment.setMediaId(source.getMediaId());
                segment.setStartMs(raw.startMs());
                segment.setEndMs(raw.endMs());
                segment.setTranscript(raw.transcript());
                segment.setOcrText(String.join("\n", raw.ocrTexts()));
                segment.setSummary(chunk.segmentSummary());
                segment.setContentHash(source.getContentHash());
                segment.setMetadata(metadata(chunk));
                segments.add(segment);
            }
        }
        return segments;
    }

    private List<KnowledgeSegment> buildOriginalEvidence(KnowledgeSource source, KnowledgeSourceVersion version) {
        VideoContext context = checkpointService.loadContext(source.getMediaId());
        if (context == null) throw new IllegalStateException("没有原始解析上下文，请先完成转写");
        List<KnowledgeSegment> rows = new ArrayList<>();
        long previousStart = -1;
        for (var raw : context.segments()) {
            if (raw.startMs() < previousStart) throw new IllegalArgumentException("原始证据时间必须有序");
            previousStart = raw.startMs();
            if (raw.transcript().isBlank() && raw.ocrTexts().isEmpty()) continue;
            var row = new KnowledgeSegment(); row.setId(UUID.randomUUID().toString());
            row.setSourceId(source.getId()); row.setVersionId(version.getId()); row.setMediaId(source.getMediaId());
            row.setStartMs(raw.startMs()); row.setEndMs(raw.endMs()); row.setTranscript(raw.transcript());
            row.setOcrText(String.join("\n",raw.ocrTexts())); row.setSummary(""); row.setContentHash(source.getContentHash());
            row.setMetadata(JSON.toJSONString(java.util.Map.of("kind","original-asr-ocr","indexProfile",indexProfile)));
            rows.add(row);
        }
        return rows;
    }

    private void upsertVectors(KnowledgeSource source, KnowledgeSourceVersion version, List<KnowledgeSegment> segments) {
        List<QdrantVectorStore.KnowledgePoint> points = new ArrayList<>(segments.size());
        for (int offset = 0; offset < segments.size(); offset += EMBEDDING_BATCH_SIZE) {
            List<KnowledgeSegment> batch = segments.subList(offset,
                    Math.min(offset + EMBEDDING_BATCH_SIZE, segments.size()));
            List<List<Double>> vectors = metrics.measure("ingest.embedding",
                    () -> embeddingUtils.embedBatch(batch.stream().map(this::vectorText).toList()));
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("Embedding 返回数量与知识分段数量不一致");
            }
            for (int i = 0; i < batch.size(); i++) {
                List<Double> vector = vectors.get(i);
                if (vector.isEmpty()) throw new IllegalStateException("Embedding 返回空向量，取消索引发布");
                KnowledgeSegment segment = batch.get(i);
                points.add(new QdrantVectorStore.KnowledgePoint(
                        segment.getId(), vector, payload(source, version, segment)));
            }
        }
        metrics.run("ingest.vector_write", () -> vectorStore.upsertKnowledge(points));
    }

    private String vectorText(KnowledgeSegment segment) {
        return String.join("\n",
                segment.getSummary() == null ? "" : segment.getSummary(),
                segment.getTranscript() == null ? "" : segment.getTranscript(),
                segment.getOcrText() == null ? "" : segment.getOcrText());
    }

    private JSONObject payload(KnowledgeSource source, KnowledgeSourceVersion version, KnowledgeSegment segment) {
        JSONObject payload = new JSONObject();
        payload.put("userId", source.getOwnerUserId());
        payload.put("spaceId", source.getSpaceId());
        payload.put("collectionId", source.getCollectionId());
        payload.put("sourceId", source.getId());
        payload.put("mediaId", segment.getMediaId());
        payload.put("segmentId", segment.getId());
        payload.put("startMs", segment.getStartMs());
        payload.put("endMs", segment.getEndMs());
        payload.put("modality", "asr+ocr");
        payload.put("contentHash", source.getContentHash());
        payload.put("indexVersion", version.getVersionNo());
        return payload;
    }

    private String metadata(VideoChunk chunk) {
        JSONObject metadata = new JSONObject();
        metadata.put("chunkStartMs", chunk.startTime());
        metadata.put("chunkEndMs", chunk.endTime());
        metadata.put("keywords", chunk.keywords());
        return metadata.toJSONString();
    }

    private KnowledgeSourceVersion currentVersion(KnowledgeSource source) {
        KnowledgeSourceVersion version = versionMapper.selectOne(new QueryWrapper<KnowledgeSourceVersion>()
                .eq("source_id", source.getId())
                .eq("version_no", source.getCurrentVersion() == null ? 1 : source.getCurrentVersion())
                .last("LIMIT 1"));
        if (version == null) {
            throw new BusinessException(com.example.server.common.ErrorCode.NOT_FOUND, "内容源版本不存在");
        }
        return version;
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
