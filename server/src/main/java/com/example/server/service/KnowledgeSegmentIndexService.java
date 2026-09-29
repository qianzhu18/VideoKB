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

    public KnowledgeSegmentIndexService(KnowledgeSourceService sourceService,
                                        KnowledgeSourceVersionMapper versionMapper,
                                        KnowledgeSegmentMapper segmentMapper,
                                        QdrantVectorStore vectorStore,
                                        AgentCheckpointService checkpointService,
                                        VideoChunkingService chunkingService,
                                        EmbeddingUtils embeddingUtils,
                                        KnowledgeAuditService auditService,
                                        @Value("${ai.embedding.model:BAAI/bge-m3}") String embeddingModel) {
        this.sourceService = sourceService;
        this.versionMapper = versionMapper;
        this.segmentMapper = segmentMapper;
        this.vectorStore = vectorStore;
        this.checkpointService = checkpointService;
        this.chunkingService = chunkingService;
        this.embeddingUtils = embeddingUtils;
        this.auditService = auditService;
        this.embeddingModel = embeddingModel;
    }

    /**
     * (Re)builds segments and vectors for a media asset. Throws on failure; callers that
     * must not propagate (the analysis pipeline) catch and call {@link #markIndexFailed}.
     *
     * <p>Deliberately NOT transactional: a rollback would also erase the FAILED status that
     * makes the failure visible. Every write is a delete-then-insert per artifact, so a
     * partially applied rebuild is idempotent under the next rebuild instead of corrupting.</p>
     */
    public List<KnowledgeSegment> indexMedia(Long mediaId) {
        KnowledgeSource source = sourceService.requireSourceByMediaId(mediaId);
        KnowledgeSourceVersion version = currentVersion(source);
        version.setStatus(STATUS_INDEXING);
        version.setParserVersion(PARSER_VERSION);
        version.setEmbeddingModel(embeddingModel);
        version.setFailureReason(null);
        versionMapper.updateById(version);
        try {
            List<KnowledgeSegment> segments = buildSegments(source, version);
            if (segments.isEmpty()) {
                throw new IllegalStateException("解析上下文为空，无法生成知识分段");
            }
            replaceSegments(source, segments);
            upsertVectors(source, version, segments);
            version.setStatus(STATUS_READY);
            versionMapper.updateById(version);
            sourceService.updateIndexStatus(source, KnowledgeSourceService.STATUS_READY);
            auditService.record(source.getOwnerUserId(), "SOURCE_INDEXED", "SOURCE", source.getId(),
                    source.getSpaceId(), source.getCollectionId(),
                    "segments=" + segments.size() + ";version=" + version.getVersionNo());
            return segments;
        } catch (RuntimeException e) {
            version.setStatus(STATUS_FAILED);
            version.setFailureReason(abbreviate(e.getMessage(), 1000));
            versionMapper.updateById(version);
            sourceService.updateIndexStatus(source, KnowledgeSourceService.STATUS_FAILED);
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
        KnowledgeSource source = sourceService.requireSourceByMediaId(mediaId);
        if (!userId.equals(source.getOwnerUserId())) {
            throw new SecurityException("无权访问该内容源");
        }
        return segmentMapper.selectList(new QueryWrapper<KnowledgeSegment>()
                .eq("media_id", mediaId)
                .orderByAsc("start_ms"));
    }

    private List<KnowledgeSegment> buildSegments(KnowledgeSource source, KnowledgeSourceVersion version) {
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

    private void replaceSegments(KnowledgeSource source, List<KnowledgeSegment> segments) {
        segmentMapper.delete(new QueryWrapper<KnowledgeSegment>()
                .eq("source_id", source.getId()));
        for (KnowledgeSegment segment : segments) {
            segmentMapper.insert(segment);
        }
    }

    private void upsertVectors(KnowledgeSource source, KnowledgeSourceVersion version, List<KnowledgeSegment> segments) {
        vectorStore.deleteSource(source.getId());
        List<QdrantVectorStore.KnowledgePoint> points = new ArrayList<>(segments.size());
        for (int offset = 0; offset < segments.size(); offset += EMBEDDING_BATCH_SIZE) {
            List<KnowledgeSegment> batch = segments.subList(offset,
                    Math.min(offset + EMBEDDING_BATCH_SIZE, segments.size()));
            List<List<Double>> vectors = embeddingUtils.embedBatch(batch.stream().map(this::vectorText).toList());
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("Embedding 返回数量与知识分段数量不一致");
            }
            for (int i = 0; i < batch.size(); i++) {
                List<Double> vector = vectors.get(i);
                if (vector.isEmpty()) continue;
                KnowledgeSegment segment = batch.get(i);
                points.add(new QdrantVectorStore.KnowledgePoint(
                        segment.getId(), vector, payload(source, version, segment)));
            }
        }
        vectorStore.upsertKnowledge(points);
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
