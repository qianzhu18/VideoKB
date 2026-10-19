package com.example.server.service;

import com.alibaba.fastjson2.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** Milvus 2.6 REST v2 native BM25. SQL remains the evidence and authorization authority. */
@Component
public class MilvusLexicalClient {
    public static final String PROFILE = "videokb-bm25-jieba-lowercase-v1";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private final boolean enabled;
    private final String url;
    private final String token;
    private final String collection;
    private final OkHttpClient http;
    private volatile boolean validated;

    public record Document(String id, Long owner, Long source, Long version, String text) {}
    public record Hit(String id, Long source, Long version, double score) {}

    public MilvusLexicalClient(@Value("${knowledge.lexical.milvus.enabled:false}") boolean enabled,
            @Value("${knowledge.lexical.milvus.url:http://localhost:19531}") String url,
            @Value("${knowledge.lexical.milvus.token:}") String token,
            @Value("${knowledge.lexical.milvus.collection:knowledge_bm25_v1}") String collection,
            @Value("${knowledge.lexical.milvus.timeout-ms:5000}") long timeoutMs) {
        this.enabled=enabled; this.url=url.replaceAll("/+$", ""); this.token=token; this.collection=collection;
        if (!collection.matches("[A-Za-z_][A-Za-z0-9_]{0,254}") || timeoutMs < 1)
            throw new IllegalArgumentException("非法 Milvus collection 或超时配置");
        this.http=new OkHttpClient.Builder().callTimeout(Duration.ofMillis(timeoutMs))
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 2000))).retryOnConnectionFailure(false).build();
    }

    public boolean enabled() { return enabled; }
    public String backendKey() { return KnowledgeBlockIndexService.hash(url + "\n" + collection + "\n" + PROFILE); }

    public synchronized void prepare() {
        requireEnabled();
        var exists=call("collections/has", Map.of("collectionName", collection)).getJSONObject("data");
        if (!exists.getBooleanValue("has")) call("collections/create", schema());
        validateExistingCollection();
        call("collections/load", Map.of("collectionName",collection));
        validated=true;
    }

    private void validateExistingCollection() {
        var description=call("collections/describe", Map.of("collectionName",collection)).getJSONObject("data");
        validateSchema(description);
        var sparseIndex=description.getJSONArray("indexes").stream().map(o -> (JSONObject)o)
                .filter(i -> "sparse".equals(i.getString("fieldName"))).findFirst().orElseThrow();
        var indexes=call("indexes/describe",Map.of("collectionName",collection,"indexName",sparseIndex.getString("indexName")))
                .getJSONArray("data");
        if (indexes==null || indexes.size()!=1) throw new IllegalStateException("Milvus sparse 索引配置缺失");
        var index=indexes.getJSONObject(0);
        var entries=index.getJSONArray("indexParams");
        JSONObject params=null;
        if (entries!=null) for (int i=0;i<entries.size();i++) {
            var entry=entries.getJSONObject(i);
            if ("params".equals(entry.getString("key"))) params=JSON.parseObject(entry.getString("value"));
        }
        if (!"SPARSE_INVERTED_INDEX".equals(index.getString("indexType")) || params==null
                || !Objects.equals(params.getDouble("bm25_k1"),1.2) || !Objects.equals(params.getDouble("bm25_b"),0.75)
                || !"DAAT_MAXSCORE".equals(params.getString("inverted_index_algo")))
            throw new IllegalStateException("Milvus BM25 参数不匹配，请使用独立 collection");
    }

    private synchronized void validateForRead() {
        if (validated) return;
        validateExistingCollection();
        validated=true;
    }

    static void validateSchema(JSONObject description) {
        var expected=Map.of("id","VarChar","owner_id","Int64","source_id","Int64",
                "version_id","Int64","text","VarChar","sparse","SparseFloatVector");
        var fields=description.getJSONArray("fields");
        boolean valid=fields!=null && fields.size()==expected.size() && !description.getBooleanValue("autoId")
                && !description.getBooleanValue("enableDynamicField");
        if (valid) for (int i=0;i<fields.size();i++) {
            var field=fields.getJSONObject(i);
            valid &= Objects.equals(expected.get(field.getString("name")),field.getString("type"));
            if ("id".equals(field.getString("name"))) valid &= field.getBooleanValue("primaryKey");
            if ("text".equals(field.getString("name"))) {
                var params=new HashMap<String,String>();
                var entries=field.getJSONArray("params");
                if (entries!=null) for (int j=0;j<entries.size();j++) {
                    var entry=entries.getJSONObject(j); params.put(entry.getString("key"),entry.getString("value"));
                }
                var analyzer=JSON.parseObject(params.get("analyzer_params"));
                valid &= "true".equals(params.get("enable_analyzer")) && analyzer!=null
                        && "jieba".equals(analyzer.getString("tokenizer"))
                        && List.of("lowercase").equals(analyzer.getList("filter",String.class));
            }
        }
        var functions=description.getJSONArray("functions");
        valid &= functions!=null && functions.size()==1;
        if (valid) {
            var function=functions.getJSONObject(0);
            valid &= function.getIntValue("type")==1 && List.of("text").equals(function.getList("inputFieldNames",String.class))
                    && List.of("sparse").equals(function.getList("outputFieldNames",String.class));
        }
        var indexes=description.getJSONArray("indexes");
        valid &= indexes!=null && indexes.stream().map(o -> (JSONObject)o)
                .anyMatch(i -> "sparse".equals(i.getString("fieldName")) && "BM25".equals(i.getString("metricType")));
        if (!valid) throw new IllegalStateException("Milvus collection schema/analyzer 不匹配，请使用独立 collection");
    }

    Map<String,Object> schema() {
        var fields=List.of(
            Map.of("fieldName","id","dataType","VarChar","isPrimary",true,"elementTypeParams",Map.of("max_length",36)),
            Map.of("fieldName","owner_id","dataType","Int64"),
            Map.of("fieldName","source_id","dataType","Int64"),
            Map.of("fieldName","version_id","dataType","Int64"),
            Map.of("fieldName","text","dataType","VarChar","elementTypeParams",Map.of("max_length",65535,
                "enable_analyzer",true,"analyzer_params",Map.of("tokenizer","jieba","filter",List.of("lowercase")))),
            Map.of("fieldName","sparse","dataType","SparseFloatVector"));
        return Map.of("collectionName",collection,"schema",Map.of("autoId",false,"enabledDynamicField",false,
            "fields",fields,"functions",List.of(Map.of("name","text_bm25",
                "type","BM25","inputFieldNames",List.of("text"),"outputFieldNames",List.of("sparse"),"params",Map.of()))),
            "indexParams",List.of(Map.of("fieldName","sparse","indexType","SPARSE_INVERTED_INDEX","metricType","BM25",
                "params",Map.of("inverted_index_algo","DAAT_MAXSCORE","bm25_k1",1.2,"bm25_b",0.75))),
            "params",Map.of("consistencyLevel","Strong"));
    }

    public void upsert(List<Document> documents) {
        requireEnabled();
        for (int offset=0;offset<documents.size();offset+=100) {
            var batch=documents.subList(offset, Math.min(offset+100,documents.size()));
            var rows=batch.stream().map(d -> Map.of("id",d.id(),"owner_id",d.owner(),"source_id",d.source(),
                    "version_id",d.version(),"text",d.text())).toList();
            var data=call("entities/upsert", Map.of("collectionName",collection,"data",rows)).getJSONObject("data");
            if (data.getIntValue("upsertCount") != batch.size()) throw new IllegalStateException("Milvus 写入数量不完整");
        }
    }

    public long count(KnowledgeQueryScope scope) {
        requireEnabled();
        if (scope.generations().isEmpty()) return 0;
        validateForRead();
        var data=call("entities/query", Map.of("collectionName",collection,"filter",filter(scope),
                "outputFields",List.of("count(*)"),"consistencyLevel","Strong")).getJSONArray("data");
        if (data == null || data.size()!=1 || !data.getJSONObject(0).containsKey("count(*)"))
            throw new IllegalStateException("Milvus 计数响应不完整");
        return data.getJSONObject(0).getLongValue("count(*)");
    }

    public List<Hit> search(String query, KnowledgeQueryScope scope, int limit) {
        requireEnabled();
        if (scope.generations().isEmpty()) return List.of();
        validateForRead();
        var data=call("entities/search", Map.of("collectionName",collection,"data",List.of(query),"annsField","sparse",
            "filter",filter(scope),"limit",limit,"outputFields",List.of("source_id","version_id"),
            "consistencyLevel","Strong","searchParams",Map.of("params",Map.of()))).getJSONArray("data");
        if (data == null) throw new IllegalStateException("Milvus 检索响应不完整");
        var hits=new ArrayList<Hit>();
        var seen=new HashSet<String>();
        for (int i=0;i<data.size();i++) {
            var row=data.getJSONObject(i);
            var score=row.getDouble("distance");
            String id=row.getString("id"); Long source=row.getLong("source_id"); Long version=row.getLong("version_id");
            if (id==null || source==null || version==null || score==null || !Double.isFinite(score))
                throw new IllegalStateException("Milvus 命中缺少身份或分数");
            if (scope.contains(source,version) && score>0 && seen.add(id)) hits.add(new Hit(id,source,version,score));
        }
        return hits;
    }

    static String filter(KnowledgeQueryScope scope) {
        if (scope.userId()==null || scope.userId()<1) throw new IllegalArgumentException("查询必须绑定主体");
        if (scope.generations().isEmpty()) return "owner_id == " + scope.userId() + " and source_id == -1";
        String generations=scope.generations().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
            if (e.getKey()<1 || e.getValue().versionId()<1) throw new IllegalArgumentException("非法查询版本");
            return "(source_id == " + e.getKey() + " and version_id == " + e.getValue().versionId() + ")";
        }).collect(Collectors.joining(" or "));
        return "owner_id == " + scope.userId() + " and (" + generations + ")";
    }

    private void requireEnabled() { if (!enabled) throw new IllegalStateException("Milvus BM25 未启用"); }
    private JSONObject call(String operation, Map<String,?> body) {
        var request=new Request.Builder().url(url + "/v2/vectordb/" + operation)
                .post(RequestBody.create(JSON.toJSONString(body),JSON_TYPE));
        if (!token.isBlank()) request.header("Authorization","Bearer " + token);
        try (var response=http.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body()==null) {
                validated=false;
                throw new IllegalStateException("Milvus HTTP 请求失败");
            }
            var result=JSON.parseObject(response.body().string());
            if (result==null || !result.containsKey("code") || result.getIntValue("code")!=0) {
                validated=false;
                throw new IllegalStateException("Milvus REST 请求失败: " + operation);
            }
            return result;
        } catch (IOException e) { validated=false; throw new IllegalStateException("Milvus 请求超时或不可用",e); }
    }
}
