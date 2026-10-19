package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class EmbeddingUtils {

    private static final int MAX_BATCH_SIZE = 32;
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final long CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Map<String, CachedEmbedding> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedEmbedding> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public EmbeddingUtils(@Value("${ai.model-gateway.api-key}") String apiKey,
                          @Value("${ai.model-gateway.base-url}") String baseUrl,
                          @Value("${ai.embedding.model:BAAI/bge-m3}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) return List.of();
        return embedBatch(List.of(text)).get(0);
    }

    /** Batch provider requests while preserving input order; blank entries yield empty vectors. */
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        // Multi-text calls are document ingestion; keep those vectors out of the hot-query cache.
        boolean cacheableQuery = texts.size() == 1;
        List<List<Double>> results = new ArrayList<>(Collections.nCopies(texts.size(), List.of()));
        Map<String, List<Integer>> positionsByText = new LinkedHashMap<>();
        long now = System.nanoTime();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) continue;
            String key = cacheKey(text);
            CachedEmbedding cached = cacheableQuery ? cache.get(key) : null;
            if (cached != null && now - cached.createdAtNanos() < CACHE_TTL_NANOS) {
                results.set(i, cached.vector());
            } else {
                if (cacheableQuery && cached != null) cache.remove(key);
                positionsByText.computeIfAbsent(text, ignored -> new ArrayList<>()).add(i);
            }
        }

        List<String> uniqueTexts = new ArrayList<>(positionsByText.keySet());
        try {
            for (int offset = 0; offset < uniqueTexts.size(); offset += MAX_BATCH_SIZE) {
                List<String> batch = uniqueTexts.subList(offset, Math.min(offset + MAX_BATCH_SIZE, uniqueTexts.size()));
                List<List<Double>> vectors = requestBatch(batch);
                for (int i = 0; i < batch.size(); i++) {
                    String text = batch.get(i);
                    List<Double> vector = vectors.get(i);
                    if (cacheableQuery) cache.put(cacheKey(text), new CachedEmbedding(vector, System.nanoTime()));
                    for (Integer target : positionsByText.get(text)) results.set(target, vector);
                }
            }
            return List.copyOf(results);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 生成失败", e);
        }
    }

    private List<List<Double>> requestBatch(List<String> texts) throws Exception {
        JSONObject requestJson = new JSONObject();
        requestJson.put("model", model);
        requestJson.put("input", texts);
        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestJson.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Embedding API failed: " + response.code());
            }
            JSONObject json = JSON.parseObject(response.body().string());
            JSONArray data = json.getJSONArray("data");
            if (data == null || data.size() != texts.size()) {
                throw new IllegalStateException("Embedding response count does not match input count");
            }
            List<List<Double>> vectors = new ArrayList<>(Collections.nCopies(texts.size(), null));
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                int index = item.containsKey("index") ? item.getIntValue("index") : i;
                if (index < 0 || index >= vectors.size() || vectors.get(index) != null) {
                    throw new IllegalStateException("Embedding response contains an invalid input index");
                }
                JSONArray values = item.getJSONArray("embedding");
                if (values == null || values.isEmpty()) throw new IllegalStateException("Embedding vector is empty");
                List<Double> vector = new ArrayList<>(values.size());
                for (Object value : values) vector.add(((Number) value).doubleValue());
                vectors.set(index, List.copyOf(vector));
            }
            if (vectors.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalStateException("Embedding response omitted one or more vectors");
            }
            return List.copyOf(vectors);
        }
    }

    private String cacheKey(String text) {
        return model + '\u0000' + text;
    }

    private record CachedEmbedding(List<Double> vector, long createdAtNanos) {
    }
}
