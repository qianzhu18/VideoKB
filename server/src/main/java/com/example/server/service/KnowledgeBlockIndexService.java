package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.KnowledgeEvidence;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class KnowledgeBlockIndexService {
    private final KnowledgeRetrievalBlockMapper blocks;
    private final KnowledgeEmbeddingCacheMapper cache;
    private final KnowledgeSourceVersionMapper versions;
    private final EmbeddingUtils embeddings;
    private final QdrantVectorStore vectors;
    private final KnowledgeMetrics metrics;
    public KnowledgeBlockIndexService(KnowledgeRetrievalBlockMapper blocks, KnowledgeEmbeddingCacheMapper cache,
            KnowledgeSourceVersionMapper versions, EmbeddingUtils embeddings, QdrantVectorStore vectors, KnowledgeMetrics metrics) {
        this.blocks = blocks; this.cache = cache; this.versions = versions;
        this.embeddings = embeddings; this.vectors = vectors; this.metrics = metrics;
    }
    public void index(KnowledgeSource source, KnowledgeSourceVersion version, List<KnowledgeSegment> originals) {
        var drafts = new EvidenceBlockChunker().split(originals);
        if (drafts.isEmpty()) throw new IllegalStateException("原始证据无法生成检索块");
        var rows = new ArrayList<KnowledgeRetrievalBlock>();
        for (var draft : drafts) {
            var row = new KnowledgeRetrievalBlock(); row.setId(UUID.randomUUID().toString());
            row.setSourceId(source.getId()); row.setVersionId(version.getId()); row.setMediaId(source.getMediaId());
            row.setStartMs(draft.evidence().getFirst().getStartMs()); row.setEndMs(draft.evidence().stream().mapToLong(KnowledgeSegment::getEndMs).max().orElseThrow());
            row.setText(draft.text()); row.setTextHash(hash(draft.text())); row.setIndexProfile(EvidenceBlockChunker.PROFILE);
            blocks.insert(row);
            for (int i=0; i<draft.evidence().size(); i++) blocks.link(row.getId(), draft.evidence().get(i).getId(), i);
            rows.add(row);
        }
        var values = cachedVectors(source.getOwnerUserId(), version.getEmbeddingModel(), rows);
        int dimension = values.getFirst().size();
        if (values.stream().anyMatch(v -> v.size() != dimension)) throw new IllegalStateException("Embedding 维度不一致，取消发布");
        version.setVectorDimension(dimension); versions.updateById(version);
        var points = new ArrayList<QdrantVectorStore.KnowledgePoint>();
        for (int i=0;i<rows.size();i++) {
            var row = rows.get(i); var payload = new JSONObject();
            payload.put("userId", source.getOwnerUserId()); payload.put("sourceId", source.getId());
            payload.put("mediaId", source.getMediaId()); payload.put("segmentId", row.getId());
            payload.put("indexVersion", version.getVersionNo()); payload.put("indexProfile", row.getIndexProfile());
            payload.put("startMs",row.getStartMs()); payload.put("endMs",row.getEndMs());
            points.add(new QdrantVectorStore.KnowledgePoint(row.getId(),values.get(i),payload));
        }
        metrics.run("ingest.vector_write", () -> vectors.upsertKnowledge(points));
    }

    List<List<Double>> cachedVectors(Long owner, String model, List<KnowledgeRetrievalBlock> rows) {
        var keys = rows.stream().map(r -> cacheKey(owner, model, r)).toList();
        var byKey = new HashMap<String, List<Double>>();
        for (var row : cache.selectBatchIds(keys.stream().distinct().toList())) {
            if (!Objects.equals(row.getOwnerUserId(),owner) || !Objects.equals(row.getModel(),model))
                throw new IllegalStateException("向量缓存主体或模型不匹配");
            var vector = JSON.parseArray(row.getVectorJson(), Double.class);
            validate(vector);
            if (vector.size() != row.getVectorDimension()) throw new IllegalStateException("向量缓存维度损坏");
            byKey.put(row.getCacheKey(), vector);
        }
        var missing = new LinkedHashMap<String, KnowledgeRetrievalBlock>();
        for (int i=0;i<keys.size();i++) if (!byKey.containsKey(keys.get(i))) missing.putIfAbsent(keys.get(i),rows.get(i));
        var absent = new ArrayList<>(missing.entrySet());
        for (int offset=0;offset<absent.size();offset+=32) {
            var batch = absent.subList(offset,Math.min(offset+32,absent.size()));
            var results = metrics.measure("ingest.embedding", () -> embeddings.embedBatch(batch.stream().map(e -> e.getValue().getText()).toList()));
            if (results.size()!=batch.size()) throw new IllegalStateException("Embedding 返回数量不匹配");
            for (int i=0;i<batch.size();i++) {
                var entry=batch.get(i); var value=results.get(i); validate(value);
                var row=new KnowledgeEmbeddingCache(); row.setCacheKey(entry.getKey()); row.setOwnerUserId(owner);
                row.setModel(model); row.setIndexProfile(entry.getValue().getIndexProfile()); row.setTextHash(entry.getValue().getTextHash());
                row.setVectorDimension(value.size()); row.setVectorJson(JSON.toJSONString(value));
                try { cache.insert(row); } catch (DuplicateKeyException race) {
                    var existing=cache.selectById(entry.getKey());
                    if (existing == null) throw race;
                    value=JSON.parseArray(existing.getVectorJson(),Double.class); validate(value);
                    if (value.size()!=existing.getVectorDimension()) throw new IllegalStateException("向量缓存维度损坏");
                }
                byKey.put(entry.getKey(),value);
            }
        }
        metrics.count("embedding_cache_hit", keys.size()-missing.size());
        return keys.stream().map(byKey::get).toList();
    }

    public List<KnowledgeSegment> resolve(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return blocks.selectBatchIds(ids).stream().map(this::projection).toList();
    }
    public List<KnowledgeSegment> inScope(KnowledgeQueryScope scope) {
        if (scope.generations().isEmpty()) return List.of();
        return blocks.selectList(new QueryWrapper<KnowledgeRetrievalBlock>().in("version_id",
                scope.generations().values().stream().map(KnowledgeQueryScope.Generation::versionId).toList())).stream()
                .filter(b -> scope.contains(b.getSourceId(), b.getVersionId())).map(this::projection).toList();
    }
    private KnowledgeSegment projection(KnowledgeRetrievalBlock block) {
        var row = new KnowledgeSegment(); row.setId(block.getId()); row.setSourceId(block.getSourceId());
        row.setVersionId(block.getVersionId()); row.setMediaId(block.getMediaId()); row.setStartMs(block.getStartMs()); row.setEndMs(block.getEndMs());
        row.setTranscript(block.getText()); row.setOcrText(""); row.setSummary("");
        row.setMetadata(JSON.toJSONString(Map.of("indexProfile",block.getIndexProfile(),"retrievalBlock",true)));
        return row;
    }
    public List<KnowledgeEvidence> evidence(KnowledgeSegment candidate) {
        if (candidate.getMetadata()!=null && JSON.parseObject(candidate.getMetadata()).getBooleanValue("retrievalBlock"))
            return blocks.evidence(candidate.getId()).stream().map(KnowledgeEvidence::from).toList();
        return List.of(KnowledgeEvidence.from(candidate));
    }
    private static void validate(List<Double> vector) {
        if (vector==null || vector.isEmpty() || vector.stream().anyMatch(v -> v==null || !Double.isFinite(v)))
            throw new IllegalStateException("Embedding 返回非法向量，取消索引发布");
    }
    static String cacheKey(Long owner, String model, KnowledgeRetrievalBlock row) {
        return hash(owner + "\n" + model + "\n" + row.getIndexProfile() + "\n" + row.getTextHash());
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
