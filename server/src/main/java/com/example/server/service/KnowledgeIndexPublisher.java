package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SQL publication is the visibility boundary; Qdrant points alone are never authority. */
@Service
public class KnowledgeIndexPublisher {
    private final KnowledgeSourceMapper sources;
    private final KnowledgeSourceVersionMapper versions;
    public KnowledgeIndexPublisher(KnowledgeSourceMapper sources, KnowledgeSourceVersionMapper versions) {
        this.sources = sources; this.versions = versions;
    }

    @Transactional
    public void publish(KnowledgeSource source, KnowledgeSourceVersion version) {
        KnowledgeSource current = sources.lockById(source.getId());
        if (current == null || "DELETED".equals(current.getStatus())) throw new IllegalStateException("内容源已删除，取消索引发布");
        if (current.getCurrentVersion() > version.getVersionNo()) throw new IllegalStateException("新版本已发布，取消过期构建");
        versions.update(null, new UpdateWrapper<KnowledgeSourceVersion>().eq("id", version.getId())
                .set("status", "READY").set("failure_reason", null));
        sources.update(null, new UpdateWrapper<KnowledgeSource>().eq("id", source.getId())
                .set("current_version", version.getVersionNo()).set("status", "READY"));
    }

    @Transactional
    public void fail(KnowledgeSource source, KnowledgeSourceVersion version, String error) {
        KnowledgeSource current = sources.lockById(source.getId());
        // A lost commit acknowledgement must not invalidate an already-visible generation.
        if (current != null && "READY".equals(current.getStatus())
                && java.util.Objects.equals(current.getCurrentVersion(), version.getVersionNo())) return;
        versions.update(null, new UpdateWrapper<KnowledgeSourceVersion>().eq("id", version.getId())
                .set("status", "FAILED").set("failure_reason", error));
        if (current != null && !"DELETED".equals(current.getStatus()) && !"READY".equals(current.getStatus())) {
            sources.update(null, new UpdateWrapper<KnowledgeSource>().eq("id", source.getId()).set("status", "FAILED"));
        }
    }
}
