package com.example.server.service;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.common.ErrorCode;
import com.example.server.dto.ScriptSourceRequest;
import com.example.server.dto.KnowledgeSourceView;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.KnowledgeSourceVersion;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.mapper.KnowledgeSourceVersionMapper;
import com.example.server.utils.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registers an uploaded script as a SCRIPT knowledge source and indexes it inline:
 * paragraphs become authoritative segment rows and segment-level vectors, the same
 * contract video sources follow, so cross-video retrieval covers scripts for free.
 * Text embedding of a few paragraphs is fast enough to stay out of the MQ pipeline.
 */
@Service
public class KnowledgeScriptService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeScriptService.class);
    /** Bumped when paragraph derivation changes so stale rows can be located. */
    public static final String SCRIPT_PARSER_VERSION = "script-paragraphs-v1";

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSpaceService spaceService;
    private final KnowledgeCollectionService collectionService;
    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeSourceVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final ScriptChunkingService chunkingService;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final KnowledgeAuditService auditService;
    private final String embeddingModel;
    private final KnowledgePlacementService placements;

    public KnowledgeScriptService(KnowledgeSourceService sourceService,
                                  KnowledgeSpaceService spaceService,
                                  KnowledgeCollectionService collectionService,
                                  KnowledgeSourceMapper sourceMapper,
                                  KnowledgeSourceVersionMapper versionMapper,
                                  KnowledgeSegmentMapper segmentMapper,
                                  ScriptChunkingService chunkingService,
                                  EmbeddingUtils embeddingUtils,
                                  QdrantVectorStore vectorStore,
                                  KnowledgeAuditService auditService,
                                  @Value("${ai.embedding.model:BAAI/bge-m3}") String embeddingModel,
                                  KnowledgePlacementService placements) {
        this.sourceService = sourceService;
        this.spaceService = spaceService;
        this.collectionService = collectionService;
        this.sourceMapper = sourceMapper;
        this.versionMapper = versionMapper;
        this.segmentMapper = segmentMapper;
        this.chunkingService = chunkingService;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.auditService = auditService;
        this.embeddingModel = embeddingModel;
        this.placements = placements;
    }

    public KnowledgeSourceView createScript(Long userId, ScriptSourceRequest request) {
        KnowledgeSource source = insertSource(userId, request);
        KnowledgeSourceVersion version = insertVersion(source);
        try {
            List<KnowledgeSegment> segments = buildSegments(source, version,
                    chunkingService.paragraphs(request.content()));
            if (segments.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "脚本内容为空，无法生成知识分段");
            }
            index(source, version, segments);
            auditService.record(userId, "SOURCE_CREATED", "SOURCE", source.getId(),
                    source.getSpaceId(), source.getCollectionId(),
                    "type=SCRIPT;paragraphs=" + segments.size());
            return KnowledgeSourceView.from(source, List.of());
        } catch (RuntimeException e) {
            version.setStatus(KnowledgeSegmentIndexService.STATUS_FAILED);
            version.setFailureReason(abbreviate(e.getMessage(), 1000));
            versionMapper.updateById(version);
            sourceService.updateIndexStatus(source, KnowledgeSourceService.STATUS_FAILED);
            throw e;
        }
    }

    private KnowledgeSource insertSource(Long userId, ScriptSourceRequest request) {
        // space_id is NOT NULL: resolve the requested owned space or the account default.
        Long spaceId = request.spaceId() != null
                ? spaceService.requireOwnedSpace(userId, request.spaceId()).getId()
                : spaceService.defaultSpaceForUser(userId).getId();
        if (request.collectionId() != null) {
            collectionService.requireCollectionInSpace(request.collectionId(), spaceId);
        }
        KnowledgeSource source = new KnowledgeSource();
        source.setSourceType(KnowledgeSourceService.SOURCE_TYPE_SCRIPT);
        source.setOwnerUserId(userId);
        source.setSpaceId(spaceId);
        source.setCollectionId(request.collectionId());
        source.setTitle(request.effectiveTitle());
        source.setContentHash(sha256(request.content()));
        source.setCurrentVersion(1);
        source.setStatus(KnowledgeSourceService.STATUS_PENDING);
        sourceMapper.insert(source);
        placements.ensurePrimary(source);
        return source;
    }

    private KnowledgeSourceVersion insertVersion(KnowledgeSource source) {
        KnowledgeSourceVersion version = new KnowledgeSourceVersion();
        version.setSourceId(source.getId());
        version.setVersionNo(1);
        version.setContentHash(source.getContentHash());
        version.setParserVersion(SCRIPT_PARSER_VERSION);
        version.setEmbeddingModel(embeddingModel);
        version.setStatus(KnowledgeSourceService.STATUS_PENDING);
        versionMapper.insert(version);
        return version;
    }

    private static List<KnowledgeSegment> buildSegments(KnowledgeSource source,
                                                        KnowledgeSourceVersion version,
                                                        List<String> paragraphs) {
        List<KnowledgeSegment> segments = new ArrayList<>(paragraphs.size());
        for (String paragraph : paragraphs) {
            KnowledgeSegment segment = new KnowledgeSegment();
            segment.setId(UUID.randomUUID().toString());
            segment.setSourceId(source.getId());
            segment.setVersionId(version.getId());
            segment.setTranscript(paragraph);
            segment.setContentHash(source.getContentHash());
            segments.add(segment);
        }
        return segments;
    }

    private void index(KnowledgeSource source, KnowledgeSourceVersion version,
                       List<KnowledgeSegment> segments) {
        version.setStatus(KnowledgeSegmentIndexService.STATUS_INDEXING);
        versionMapper.updateById(version);
        try {
            for (KnowledgeSegment segment : segments) {
                segmentMapper.insert(segment);
            }
            vectorStore.deleteSource(source.getId());
            List<QdrantVectorStore.KnowledgePoint> points = new ArrayList<>(segments.size());
            for (KnowledgeSegment segment : segments) {
                List<Double> vector = embeddingUtils.embed(segment.getTranscript());
                if (vector.isEmpty()) continue;
                points.add(new QdrantVectorStore.KnowledgePoint(
                        segment.getId(), vector, payload(source, version, segment)));
            }
            vectorStore.upsertKnowledge(points);
            version.setStatus(KnowledgeSegmentIndexService.STATUS_READY);
            versionMapper.updateById(version);
            sourceService.updateIndexStatus(source, KnowledgeSourceService.STATUS_READY);
            auditService.record(source.getOwnerUserId(), "SOURCE_INDEXED", "SOURCE", source.getId(),
                    source.getSpaceId(), source.getCollectionId(),
                    "segments=" + segments.size() + ";type=SCRIPT");
        } catch (RuntimeException e) {
            // Keep the failure visible on the version row; a rebuild re-runs idempotently.
            log.warn("script_index_failed sourceId={}", source.getId(), e);
            throw e;
        }
    }

    private JSONObject payload(KnowledgeSource source, KnowledgeSourceVersion version,
                               KnowledgeSegment segment) {
        JSONObject payload = new JSONObject();
        payload.put("userId", source.getOwnerUserId());
        payload.put("spaceId", source.getSpaceId());
        payload.put("collectionId", source.getCollectionId());
        payload.put("sourceId", source.getId());
        payload.put("segmentId", segment.getId());
        payload.put("modality", "text");
        payload.put("contentHash", source.getContentHash());
        payload.put("indexVersion", version.getVersionNo());
        return payload;
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算脚本内容哈希", e);
        }
    }

    /** Loads the authoritative segment rows of a source for the link service. */
    public List<KnowledgeSegment> segmentsOf(Long sourceId) {
        return segmentMapper.selectList(new QueryWrapper<KnowledgeSegment>()
                .eq("source_id", sourceId)
                .orderByAsc("id"));
    }

    private static String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
