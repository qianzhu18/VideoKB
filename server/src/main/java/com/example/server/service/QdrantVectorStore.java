package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.VideoChunk;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class QdrantVectorStore implements KnowledgeVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Map<String, String> FILTER_INDEXES = Map.of(
            "userId", "integer", "spaceId", "integer", "collectionId", "integer",
            "sourceId", "integer", "mediaId", "integer", "indexVersion", "integer");

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final AtomicBoolean collectionReady = new AtomicBoolean();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public QdrantVectorStore(@Value("${vector.qdrant.enabled:true}") boolean enabled,
                             @Value("${vector.qdrant.url:http://localhost:6333}") String baseUrl,
                             @Value("${vector.qdrant.api-key:}") String apiKey,
                             @Value("${vector.qdrant.collection:video_chunks}") String collection) {
        if (!collection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant collection name is invalid");
        }
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.collection = collection;
    }

    public void upsert(Long mediaId, List<VideoChunk> chunks) {
        if (!enabled) return;
        List<VideoChunk> vectorized = chunks.stream().filter(chunk -> !chunk.embedding().isEmpty()).toList();
        if (vectorized.isEmpty()) return;
        try {
            ensureCollection(vectorized.get(0).embedding().size());
            JSONArray points = new JSONArray();
            for (VideoChunk chunk : vectorized) {
                JSONObject payload = new JSONObject();
                payload.put("mediaId", mediaId);
                payload.put("startMs", chunk.startTime());
                payload.put("endMs", chunk.endTime());

                JSONObject point = new JSONObject();
                point.put("id", pointId(mediaId, chunk));
                point.put("vector", chunk.embedding());
                point.put("payload", payload);
                points.add(point);
            }
            JSONObject body = new JSONObject();
            body.put("points", points);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 分段向量写入失败", e);
        }
    }

    public List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit) {
        if (!enabled || queryEmbedding.isEmpty()) return List.of();
        try {
            ensureCollection(queryEmbedding.size());
            JSONObject match = new JSONObject();
            match.put("value", mediaId);
            JSONObject condition = new JSONObject();
            condition.put("key", "mediaId");
            condition.put("match", match);
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(condition));

            JSONObject body = new JSONObject();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));

            JSONObject result = JSON.parseObject(response).getJSONObject("result");
            JSONArray points = result == null ? null : result.getJSONArray("points");
            if (points == null) return List.of();
            List<VectorHit> hits = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                JSONObject payload = point.getJSONObject("payload");
                if (payload == null) continue;
                hits.add(new VectorHit(
                        payload.getLongValue("startMs"),
                        payload.getLongValue("endMs"),
                        point.getDoubleValue("score")));
            }
            return hits;
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 语义检索失败", e);
        }
    }

    /**
     * Writes knowledge-segment vectors carrying the ownership payload (userId, spaceId,
     * segmentId, ...). Points are addressed by segment UUID so a rebuild of the same
     * segment overwrites in place instead of duplicating.
     */
    public void upsertKnowledge(List<KnowledgePoint> points) {
        if (!enabled || points.isEmpty()) return;
        try {
            ensureCollection(points.get(0).vector().size());
            JSONArray array = new JSONArray();
            for (KnowledgePoint point : points) {
                JSONObject object = new JSONObject();
                object.put("id", point.id());
                object.put("vector", point.vector());
                object.put("payload", point.payload());
                array.add(object);
            }
            JSONObject body = new JSONObject();
            body.put("points", array);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 知识向量写入失败", e);
        }
    }

    /** Similarity search restricted to one user's knowledge space (and optional folder). */
    public List<KnowledgeHit> searchKnowledge(List<Double> queryEmbedding,
                                              Long userId,
                                              Long spaceId,
                                              Long collectionId,
                                              int limit) {
        if (!enabled || queryEmbedding.isEmpty()) return List.of();
        try {
            ensureCollection(queryEmbedding.size());
            JSONArray must = new JSONArray();
            must.add(matchCondition("userId", userId));
            must.add(matchCondition("spaceId", spaceId));
            if (collectionId != null) {
                must.add(matchCondition("collectionId", collectionId));
            }
            JSONObject filter = new JSONObject();
            filter.put("must", must);

            return queryKnowledge(queryEmbedding, filter, limit);
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 知识检索失败", e);
        }
    }

    @Override
    public List<KnowledgeVectorIndex.Hit> search(List<Double> vector, KnowledgeQueryScope scope, int limit) {
        if (!enabled || vector.isEmpty() || scope.generations().isEmpty()) return List.of();
        ensureCollection(vector.size());
        // Keep each HTTP filter bounded. Global top-K is the top-K union of each partition.
        var entries = new ArrayList<>(scope.generations().entrySet());
        List<KnowledgeHit> all = new ArrayList<>();
        for (int offset = 0; offset < entries.size(); offset += 200) {
            JSONArray should = new JSONArray();
            for (var entry : entries.subList(offset, Math.min(entries.size(), offset + 200))) {
                JSONObject generation = new JSONObject();
                generation.put("must", List.of(matchCondition("sourceId", entry.getKey()),
                        matchCondition("indexVersion", entry.getValue().versionNo())));
                should.add(generation);
            }
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(matchCondition("userId", scope.userId())));
            filter.put("should", should);
            all.addAll(queryKnowledge(vector, filter, limit));
        }
        return all.stream().sorted(java.util.Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(limit).map(hit -> new KnowledgeVectorIndex.Hit(hit.segmentId(), hit.sourceId(), hit.score())).toList();
    }

    private List<KnowledgeHit> queryKnowledge(List<Double> queryEmbedding, JSONObject filter, int limit) {
            JSONObject body = new JSONObject();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));

            JSONObject result = JSON.parseObject(response).getJSONObject("result");
            JSONArray points = result == null ? null : result.getJSONArray("points");
            if (points == null) return List.of();
            List<KnowledgeHit> hits = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                JSONObject payload = point.getJSONObject("payload");
                if (payload == null) continue;
                hits.add(new KnowledgeHit(
                        payload.getString("segmentId"),
                        payload.getLong("sourceId"),
                        payload.getLong("mediaId"),
                        payload.getLongValue("startMs"),
                        payload.getLongValue("endMs"),
                        point.getDoubleValue("score")));
            }
            return hits;
    }

    /** Removes every vector derived from one source; used before a rebuild to avoid stale recall. */
    public void deleteSource(Long sourceId) {
        if (!enabled) return;
        try {
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(matchCondition("sourceId", sourceId)));
            JSONObject body = new JSONObject();
            body.put("filter", filter);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            log.warn("qdrant_source_cleanup_failed sourceId={}", sourceId, e);
        }
    }

    /**
     * Re-stamps the location payload of one source after a move. Without this the points
     * keep the old spaceId and become invisible to space-filtered search. A null collection
     * id is removed from the payload instead of being written as null.
     */
    public void updateSourceLocation(Long sourceId, Long spaceId, Long collectionId) {
        if (!enabled) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("spaceId", spaceId);
            if (collectionId != null) payload.put("collectionId", collectionId);
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(matchCondition("sourceId", sourceId)));
            JSONObject body = new JSONObject();
            body.put("payload", payload);
            body.put("filter", filter);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/payload?wait=true")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
            if (collectionId == null) {
                JSONObject removal = new JSONObject();
                removal.put("keys", List.of("collectionId"));
                removal.put("filter", filter);
                execute(new Request.Builder()
                        .url(baseUrl + "/collections/" + collection + "/points/payload/delete?wait=true")
                        .post(RequestBody.create(removal.toString(), JSON_MEDIA_TYPE)));
            }
        } catch (RuntimeException e) {
            log.warn("qdrant_source_payload_update_failed sourceId={}", sourceId, e);
        }
    }

    private JSONObject matchCondition(String key, Object value) {
        JSONObject match = new JSONObject();
        match.put("value", value);
        JSONObject condition = new JSONObject();
        condition.put("key", key);
        condition.put("match", match);
        return condition;
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        try {
            JSONObject match = new JSONObject();
            match.put("value", mediaId);
            JSONObject condition = new JSONObject();
            condition.put("key", "mediaId");
            condition.put("match", match);
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(condition));
            JSONObject body = new JSONObject();
            body.put("filter", filter);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            log.warn("qdrant_media_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private void ensureCollection(int vectorSize) {
        if (collectionReady.get()) return;
        synchronized (collectionReady) {
            if (collectionReady.get()) return;
            Request.Builder lookup = request(baseUrl + "/collections/" + collection).get();
            try (Response response = client.newCall(lookup.build()).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body() == null ? "{}" : response.body().string();
                    JSONObject collectionInfo = JSON.parseObject(responseBody).getJSONObject("result");
                    ensureFilterIndexes(collectionInfo);
                    collectionReady.set(true);
                    return;
                }
                if (response.code() != 404) {
                    throw new IllegalStateException("Qdrant collection lookup failed: " + response.code());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Qdrant collection lookup failed", e);
            }

            JSONObject vectors = new JSONObject();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            JSONObject body = new JSONObject();
            body.put("vectors", vectors);
            execute(request(baseUrl + "/collections/" + collection)
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
            ensureFilterIndexes(new JSONObject());
            collectionReady.set(true);
        }
    }

    /** Add indexes for fields used by ownership and media filters before the next bulk ingest. */
    private void ensureFilterIndexes(JSONObject collectionInfo) {
        JSONObject schemas = collectionInfo == null ? null : collectionInfo.getJSONObject("payload_schema");
        for (Map.Entry<String, String> index : FILTER_INDEXES.entrySet()) {
            if (schemas != null && schemas.containsKey(index.getKey())) continue;
            JSONObject body = new JSONObject();
            body.put("field_name", index.getKey());
            body.put("field_schema", index.getValue());
            execute(request(baseUrl + "/collections/" + collection + "/index?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        }
    }

    private String execute(Request.Builder request) {
        try (Response response = client.newCall(withApiKey(request).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Qdrant API failed: " + response.code() + " " + body);
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant request failed", e);
        }
    }

    private Request.Builder request(String url) {
        return withApiKey(new Request.Builder().url(url));
    }

    private Request.Builder withApiKey(Request.Builder request) {
        if (!apiKey.isBlank()) request.header("api-key", apiKey);
        return request;
    }

    private String pointId(Long mediaId, VideoChunk chunk) {
        String source = mediaId + ":" + chunk.startTime() + ":" + chunk.endTime();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record VectorHit(long startMs, long endMs, double score) {
    }

    /** One knowledge-segment vector point ready for an upsert. */
    public record KnowledgePoint(String id, List<Double> vector, JSONObject payload) {
    }

    /** Recall result of a knowledge-space search; evidence text lives in MySQL, not here. */
    public record KnowledgeHit(String segmentId, Long sourceId, Long mediaId,
                               long startMs, long endMs, double score) {
    }
}
