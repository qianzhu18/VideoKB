package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.KnowledgeSearchHit;
import com.example.server.dto.KnowledgeSearchRequest;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-video retrieval inside one knowledge space: vector recall via Qdrant filtered by
 * ownership, term-coverage keyword recall in MySQL as the fallback, and evidence backfill
 * from the authoritative segment rows. An empty result is a valid answer — no evidence,
 * no claim.
 */
@Service
public class KnowledgeSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_KEYWORD_HITS = 40;
    private static final int MAX_KEYWORD_TERMS = 8;
    /** Standard RRF damping constant; rank r contributes 1/(K+r) to the fused score. */
    private static final int RRF_K = 60;
    private static final int RECALL_MULTIPLIER = 3;

    public static final String STRATEGY_VECTOR = "vector";
    public static final String STRATEGY_KEYWORD = "keyword";
    public static final String STRATEGY_HYBRID = "hybrid";

    private final KnowledgeSpaceService spaceService;
    private final KnowledgeCollectionService collectionService;
    private final KnowledgeSegmentMapper segmentMapper;
    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeVectorIndex vectorStore;
    private final KnowledgeScopeResolver scopes;
    private final KnowledgeMetrics metrics;
    private final java.util.concurrent.Executor executor;
    private final EmbeddingUtils embeddingUtils;
    /**
     * Minimum cosine similarity for a vector hit to be surfaced. Golden-set tuning on the
     * demo corpus: no threshold maximizes recall@5 (0.96) but never refuses (0/6); 0.50
     * refuses 4/6 but drops recall@5 to 0.63; 0.45 balances both (recall@5 0.83, refusal
     * 1/6). Re-tune per corpus via the property — scores are embedding-dependent.
     */
    private final double minVectorScore;
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeBlockIndexService blockIndex;
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeLexicalIndexService lexicalIndex;

    public record SearchResult(List<KnowledgeSearchHit> hits, List<String> warnings) {}

    public KnowledgeSearchService(KnowledgeSpaceService spaceService,
                                  KnowledgeCollectionService collectionService,
                                  KnowledgeSegmentMapper segmentMapper,
                                  KnowledgeSourceMapper sourceMapper,
                                  KnowledgeVectorIndex vectorStore,
                                  EmbeddingUtils embeddingUtils,
                                  @Value("${knowledge.search.min-vector-score:0.45}") double minVectorScore, KnowledgeScopeResolver scopes, KnowledgeMetrics metrics,
                                  @org.springframework.beans.factory.annotation.Qualifier("knowledgeRetrievalExecutor") java.util.concurrent.Executor executor) {
        this.spaceService = spaceService;
        this.collectionService = collectionService;
        this.segmentMapper = segmentMapper;
        this.sourceMapper = sourceMapper;
        this.vectorStore = vectorStore;
        this.embeddingUtils = embeddingUtils;
        this.minVectorScore = minVectorScore;
        this.scopes = scopes;
        this.metrics = metrics;
        this.executor = executor;
    }

    public List<KnowledgeSearchHit> search(Long userId, KnowledgeSearchRequest request) {
        return searchWithDiagnostics(userId, request).hits();
    }

    public SearchResult searchWithDiagnostics(Long userId, KnowledgeSearchRequest request) {
        var warnings = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var hits = metrics.measure("search." + normalizeStrategy(request.strategy()), () -> doSearch(userId, request, warnings));
        return new SearchResult(hits, warnings.stream().distinct().sorted().toList());
    }

    private List<KnowledgeSearchHit> doSearch(Long userId, KnowledgeSearchRequest request, java.util.Queue<String> warnings) {
        KnowledgeQueryScope scope = scopes.resolve(userId, request.spaceId(), request.collectionId());
        if (scope.generations().isEmpty()) return List.of();
        int topK = normalizeTopK(request.topK());
        String query = request.query().trim();

        List<Recalled> recalled = recall(scope, request, query, topK, warnings);
        return backfill(recalled, userId, request, scope);
    }

    private List<Recalled> recall(KnowledgeQueryScope scope, KnowledgeSearchRequest request, String query, int topK, java.util.Queue<String> warnings) {
        String strategy = normalizeStrategy(request.strategy());
        return switch (strategy) {
            case STRATEGY_VECTOR -> vectorRecall(scope, request, query, topK);
            case "like" -> keywordRecall(scope, request, query, topK);
            case STRATEGY_KEYWORD -> lexicalRecall(scope, request, query, topK, warnings);
            default -> hybridRecall(scope, request, query, topK, warnings);
        };
    }

    private List<Recalled> vectorRecall(KnowledgeQueryScope scope, KnowledgeSearchRequest request, String query, int topK) {
        List<Double> queryEmbedding = embed(query);
        if (queryEmbedding.isEmpty()) return List.of();
        try {
            List<KnowledgeVectorIndex.Hit> hits = vectorStore
                    .search(queryEmbedding, scope, topK)
                    .stream()
                    .filter(hit -> hit.score() >= minVectorScore)
                    .toList();
            return backfillBySegmentId(hits, scope);
        } catch (RuntimeException e) {
            metrics.count("vector_recall_failed");
            return List.of();
        }
    }

    /**
     * Recalls from both channels and fuses with Reciprocal Rank Fusion: a segment found by
     * both channels outranks ones found by either alone, the quality improvement must be measured
     * on a fixed corpus; fusion alone does not guarantee better retrieval.
     */
    private List<Recalled> hybridRecall(KnowledgeQueryScope scope, KnowledgeSearchRequest request, String query, int topK, java.util.Queue<String> warnings) {
        int recallLimit = topK * RECALL_MULTIPLIER;
        var vectorFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> metrics.measure("recall.vector", () -> vectorRecall(scope, request, query, recallLimit)), executor);
        var keywordFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> metrics.measure("recall.keyword", () -> lexicalRecall(scope, request, query, recallLimit, warnings)), executor);
        List<Recalled> vectorHits = vectorFuture.join();
        List<Recalled> keywordHits = keywordFuture.join();

        Map<String, Recalled> bySegment = new LinkedHashMap<>();
        vectorHits.forEach(hit -> bySegment.put(hit.segment().getId(), hit));
        keywordHits.forEach(hit -> bySegment.putIfAbsent(hit.segment().getId(), hit));

        Map<String, Double> fused = new LinkedHashMap<>();
        for (int rank = 0; rank < vectorHits.size(); rank++) {
            fused.merge(vectorHits.get(rank).segment().getId(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < keywordHits.size(); rank++) {
            fused.merge(keywordHits.get(rank).segment().getId(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        return bySegment.values().stream()
                .sorted(Comparator.comparingDouble(
                                (Recalled hit) -> fused.getOrDefault(hit.segment().getId(), 0.0)).reversed()
                        .thenComparingLong(hit -> hit.segment().getStartMs() == null
                                ? 0 : hit.segment().getStartMs()))
                .limit(topK)
                .map(hit -> new Recalled(hit.segment(), fused.getOrDefault(hit.segment().getId(), 0.0),
                        STRATEGY_HYBRID))
                .toList();
    }

    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) return STRATEGY_HYBRID;
        return switch (strategy.trim().toLowerCase()) {
            case STRATEGY_VECTOR -> STRATEGY_VECTOR;
            case STRATEGY_KEYWORD -> STRATEGY_KEYWORD;
            case "like" -> "like";
            default -> STRATEGY_HYBRID;
        };
    }

    private List<Recalled> lexicalRecall(KnowledgeQueryScope scope, KnowledgeSearchRequest request, String query,
                                         int topK, java.util.Queue<String> warnings) {
        if (lexicalIndex == null || !lexicalIndex.enabled()) return keywordRecall(scope, request, query, topK);
        try {
            var recalled = lexicalIndex.search(query, scope, topK);
            var byId = new LinkedHashMap<String, KnowledgeSegment>();
            var ids = recalled.stream().map(MilvusLexicalClient.Hit::id).toList();
            if (ids.isEmpty()) return List.of();
            for (var row : segmentMapper.selectBatchIds(ids)) byId.put(row.getId(), row);
            if (blockIndex != null) for (var row : blockIndex.resolve(ids)) byId.put(row.getId(), row);
            var results = new ArrayList<Recalled>();
            for (var hit : recalled) {
                var row = byId.get(hit.id());
                if (row != null && java.util.Objects.equals(row.getSourceId(), hit.source())
                        && java.util.Objects.equals(row.getVersionId(), hit.version())
                        && scope.contains(row.getSourceId(), row.getVersionId()))
                    results.add(new Recalled(row, hit.score(), "bm25"));
            }
            return results;
        } catch (RuntimeException failure) {
            metrics.count("lexical_recall_failed");
            warnings.add("BM25 词法索引未就绪或不可用，已降级为兼容关键词检索；精确术语召回可能下降。");
            return keywordRecall(scope, request, query, topK);
        }
    }

    private List<Recalled> keywordRecall(KnowledgeQueryScope scope, KnowledgeSearchRequest request, String query, int topK) {
        var versionIds = scope.generations().values().stream().map(KnowledgeQueryScope.Generation::versionId).toList();
        List<String> needles = terms(query).stream().map(KnowledgeSearchService::needle).distinct().toList();
        if (needles.isEmpty()) return List.of();

        // Recall is per-needle substring match (ANY), then ranked in memory by how many
        // distinct needles a segment covers, so natural-language questions still find evidence.
        List<KnowledgeSegment> matched = segmentMapper.selectList(new QueryWrapper<KnowledgeSegment>()
                .in("version_id", versionIds)
                .and(wrapper -> {
                    boolean first = true;
                    for (String needle : needles) {
                        if (!first) wrapper.or();
                        wrapper.like("transcript", needle).or().like("ocr_text", needle).or().like("summary", needle);
                        first = false;
                    }
                })
                .orderByAsc("start_ms")
                .last("LIMIT " + MAX_KEYWORD_HITS));
        if (blockIndex != null) {
            matched = new ArrayList<>(matched);
            var blockCandidates = blockIndex.inScope(scope);
            var blockVersions = blockCandidates.stream().map(KnowledgeSegment::getVersionId).collect(java.util.stream.Collectors.toSet());
            matched.removeIf(raw -> blockVersions.contains(raw.getVersionId()));
            for (var block : blockCandidates) if (coverage(needles,block)>0) matched.add(block);
        }
        if (matched.isEmpty()) return List.of();

        List<Recalled> scored = new ArrayList<>(matched.size());
        for (KnowledgeSegment segment : matched) {
            scored.add(new Recalled(segment, coverage(needles, segment), "keyword"));
        }
        scored.sort(Comparator.comparingDouble(Recalled::score).reversed()
                .thenComparingLong(recalled -> recalled.segment().getStartMs() == null
                        ? 0 : recalled.segment().getStartMs()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    /** Long CJK phrases rarely substring-match; their leading window is the useful needle. */
    private static String needle(String term) {
        return term.length() > 12 ? term.substring(0, 8) : term;
    }

    private double coverage(List<String> needles, KnowledgeSegment segment) {
        String haystack = String.join("\n",
                segment.getTranscript() == null ? "" : segment.getTranscript(),
                segment.getOcrText() == null ? "" : segment.getOcrText(),
                segment.getSummary() == null ? "" : segment.getSummary());
        long covered = needles.stream().filter(haystack::contains).count();
        return needles.isEmpty() ? 0 : (double) covered / needles.size();
    }

    private List<Recalled> backfillBySegmentId(List<KnowledgeVectorIndex.Hit> hits, KnowledgeQueryScope scope) {
        if (hits.isEmpty()) return List.of();
        List<String> segmentIds = hits.stream().map(KnowledgeVectorIndex.Hit::segmentId).toList();
        Map<String, KnowledgeSegment> byId = new LinkedHashMap<>();
        for (KnowledgeSegment segment : segmentMapper.selectBatchIds(segmentIds)) {
            byId.put(segment.getId(), segment);
        }
        if (blockIndex != null) for (var block : blockIndex.resolve(segmentIds)) byId.put(block.getId(),block);
        List<Recalled> ordered = new ArrayList<>(hits.size());
        for (KnowledgeVectorIndex.Hit hit : hits) {
            KnowledgeSegment segment = byId.get(hit.segmentId());
            if (segment != null && java.util.Objects.equals(hit.sourceId(), segment.getSourceId())
                    && scope.contains(segment.getSourceId(), segment.getVersionId())) ordered.add(new Recalled(segment, hit.score(), "vector"));
        }
        return ordered;
    }

    private List<KnowledgeSearchHit> backfill(List<Recalled> recalled, Long userId, KnowledgeSearchRequest request, KnowledgeQueryScope snapshot) {
        if (recalled.isEmpty()) return List.of();
        KnowledgeQueryScope currentScope = scopes.resolve(userId, request.spaceId(), request.collectionId());
        Map<Long, KnowledgeSource> sources = new LinkedHashMap<>();
        for (Recalled candidate : recalled) {
            Long sourceId = candidate.segment().getSourceId();
            if (!sources.containsKey(sourceId)) {
                KnowledgeSource source = sourceMapper.selectById(sourceId);
                if (source != null) sources.put(sourceId, source);
            }
        }
        List<KnowledgeSearchHit> hits = new ArrayList<>(recalled.size());
        for (Recalled candidate : recalled) {
            KnowledgeSource source = sources.get(candidate.segment().getSourceId());
            if (source == null || !userId.equals(source.getOwnerUserId()) || !"READY".equals(source.getStatus())
                    || !currentScope.generations().containsKey(source.getId())
                    || !snapshot.contains(source.getId(), candidate.segment().getVersionId())) continue;
            KnowledgeSegment segment = candidate.segment();
            hits.add(new KnowledgeSearchHit(
                    segment.getId(),
                    source.getId(),
                    source.getSourceType(),
                    segment.getMediaId(),
                    source.getTitle(),
                    segment.getStartMs() == null ? 0 : segment.getStartMs(),
                    segment.getEndMs() == null ? 0 : segment.getEndMs(),
                    candidate.score(),
                    segment.getTranscript(),
                    segment.getOcrText(),
                    segment.getSummary(),
                    candidate.matchType(), segment.getVersionId(),
                    segment.getMetadata() == null ? "legacy-v1" : java.util.Objects.toString(com.alibaba.fastjson2.JSON.parseObject(segment.getMetadata()).getString("indexProfile"),"legacy-v1"),
                    blockIndex == null ? List.of(com.example.server.dto.KnowledgeEvidence.from(segment)) : blockIndex.evidence(segment)));
        }
        return hits;
    }

    private List<String> terms(String query) {
        return Arrays.stream(query.split("[\\s，。！？、,.;:：；!?（）()\\[\\]]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(MAX_KEYWORD_TERMS)
                .toList();
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK < 1) return DEFAULT_TOP_K;
        return Math.min(topK, MAX_TOP_K);
    }

    private List<Double> embed(String text) {
        try {
            return metrics.measure("query.embedding", () -> embeddingUtils.embed(text));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private record Recalled(KnowledgeSegment segment, double score, String matchType) {
    }
}
