package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional native BM25 index of retrieval blocks, with immutable-generation completion receipts. */
@Service
public class KnowledgeLexicalIndexService {
    private final MilvusLexicalClient client;
    private final KnowledgeLexicalGenerationMapper receipts;
    private final KnowledgeBlockIndexService blocks;
    private final KnowledgeSegmentMapper segments;
    private final KnowledgeSourceVersionMapper versions;
    private final KnowledgeSourceService sources;
    private final RedissonClient locks;
    public KnowledgeLexicalIndexService(MilvusLexicalClient client, KnowledgeLexicalGenerationMapper receipts,
            KnowledgeBlockIndexService blocks, KnowledgeSegmentMapper segments, KnowledgeSourceVersionMapper versions,
            KnowledgeSourceService sources, RedissonClient locks) {
        this.client=client; this.receipts=receipts; this.blocks=blocks; this.segments=segments;
        this.versions=versions; this.sources=sources; this.locks=locks;
    }
    public boolean enabled() { return client.enabled(); }

    /** Called before SQL publication; an enabled BM25 write failure preserves the old READY generation. */
    public int index(KnowledgeSource source, KnowledgeSourceVersion version) {
        if (!enabled()) return 0;
        var scope=new KnowledgeQueryScope(source.getOwnerUserId(), Map.of(source.getId(),
                new KnowledgeQueryScope.Generation(version.getId(),version.getVersionNo())));
        List<KnowledgeSegment> rows=blocks.inScope(scope);
        if (rows.isEmpty()) {
            if (!"legacy-v1".equals(version.getIndexProfile())) throw new IllegalStateException("当前版本检索块缺失");
            rows=segments.selectList(new QueryWrapper<KnowledgeSegment>().eq("source_id",source.getId())
                    .eq("version_id",version.getId()));
        }
        var documents=rows.stream().filter(r -> scope.contains(r.getSourceId(),r.getVersionId()))
                .map(r -> new MilvusLexicalClient.Document(r.getId(),scope.userId(),r.getSourceId(),r.getVersionId(),
                    Objects.toString(r.getTranscript(),"") + "\n" + Objects.toString(r.getOcrText(),"")))
                .filter(d -> !d.text().isBlank()).toList();
        if (documents.isEmpty()) throw new IllegalStateException("当前版本没有可索引原文");
        client.prepare();
        client.upsert(documents);
        if (client.count(scope)!=documents.size()) throw new IllegalStateException("Milvus generation 对账失败");
        receipts.complete(client.backendKey(),version.getId(),source.getId(),scope.userId(),documents.size());
        return documents.size();
    }

    /** Lexical-only backfill: no new evidence version or dense re-embedding. Same lock as reindex. */
    public int backfill(Long userId, Long sourceId) {
        var source=sources.requireOwnedSource(userId,sourceId);
        var lock=locks.getLock("knowledge:index:" + source.getMediaId());
        boolean acquired=false;
        try {
            acquired=lock.tryLock(5,TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("知识索引正在构建，请稍后重试");
            source=sources.requireOwnedSource(userId,sourceId);
            if (!"READY".equals(source.getStatus())) throw new IllegalStateException("内容尚未就绪");
            var version=versions.selectOne(new QueryWrapper<KnowledgeSourceVersion>().eq("source_id",sourceId)
                    .eq("version_no",source.getCurrentVersion()).eq("status","READY").last("LIMIT 1"));
            if (version==null) throw new IllegalStateException("当前版本尚未发布");
            if (!enabled()) throw new IllegalStateException("Milvus BM25 未启用");
            return index(source,version);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("等待索引锁被中断",e);
        } finally { if (acquired && lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    public List<MilvusLexicalClient.Hit> search(String query, KnowledgeQueryScope scope, int limit) {
        if (scope.generations().isEmpty()) return List.of();
        var completed=receipts.find(client.backendKey(),scope.generations().values().stream()
                .map(KnowledgeQueryScope.Generation::versionId).toList());
        long expected=0;
        var found=new HashSet<Long>();
        for (var receipt:completed) {
            if (!Objects.equals(receipt.getOwnerUserId(),scope.userId())
                    || !scope.contains(receipt.getSourceId(),receipt.getVersionId()) || receipt.getDocumentCount()<1)
                throw new IllegalStateException("BM25 generation 回执不一致");
            expected+=receipt.getDocumentCount(); found.add(receipt.getSourceId());
        }
        if (!found.equals(scope.generations().keySet())) throw new IllegalStateException("BM25 当前范围未完成回灌");
        if (client.count(scope)!=expected) throw new IllegalStateException("BM25 数据缺失，需重新回灌");
        return client.search(query,scope,limit);
    }
}
