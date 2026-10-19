package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class KnowledgeScopeResolver {
    private final KnowledgeSpaceService spaces;
    private final KnowledgeCollectionService collections;
    private final KnowledgePlacementMapper placements;
    private final KnowledgeSourceMapper sources;
    private final KnowledgeSourceVersionMapper versions;
    public KnowledgeScopeResolver(KnowledgeSpaceService spaces, KnowledgeCollectionService collections,
            KnowledgePlacementMapper placements, KnowledgeSourceMapper sources, KnowledgeSourceVersionMapper versions) {
        this.spaces = spaces; this.collections = collections; this.placements = placements;
        this.sources = sources; this.versions = versions;
    }

    public KnowledgeQueryScope resolve(Long userId, Long spaceId, Long collectionId) {
        spaceId = effectiveSpace(userId, spaceId, collectionId);
        spaces.requireOwnedSpace(userId, spaceId);
        var query = new QueryWrapper<KnowledgePlacement>().eq("space_id", spaceId);
        if (collectionId != null) {
            List<Long> subtree = collections.subtreeIds(spaceId, collectionId);
            if (subtree.isEmpty()) return new KnowledgeQueryScope(userId, java.util.Map.of());
            query.in("collection_id", subtree);
        }
        var ids = placements.selectList(query).stream().map(KnowledgePlacement::getSourceId).distinct().toList();
        var generations = new LinkedHashMap<Long, KnowledgeQueryScope.Generation>();
        if (ids.isEmpty()) return new KnowledgeQueryScope(userId, generations);
        var candidates = sources.selectList(new QueryWrapper<KnowledgeSource>().in("id", ids)
                .eq("owner_user_id", userId).eq("status", "READY"));
        if (candidates.isEmpty()) return new KnowledgeQueryScope(userId, generations);
        var ready = versions.selectList(new QueryWrapper<KnowledgeSourceVersion>()
                .in("source_id", candidates.stream().map(KnowledgeSource::getId).toList()).eq("status", "READY"));
        var sourceById = candidates.stream().collect(java.util.stream.Collectors.toMap(KnowledgeSource::getId, s -> s));
        for (var version : ready) {
            var source = sourceById.get(version.getSourceId());
            if (source != null && userId.equals(source.getOwnerUserId()) && "READY".equals(source.getStatus())
                    && java.util.Objects.equals(source.getCurrentVersion(), version.getVersionNo())) {
                generations.put(source.getId(), new KnowledgeQueryScope.Generation(version.getId(), version.getVersionNo()));
            }
        }
        return new KnowledgeQueryScope(userId, java.util.Map.copyOf(generations));
    }

    public Long effectiveSpace(Long userId, Long spaceId, Long collectionId) {
        if (spaceId == null && collectionId != null)
            throw new com.example.server.exception.BusinessException(com.example.server.common.ErrorCode.INVALID_ARGUMENT,
                    "目录查询必须显式提供知识空间");
        Long effective = spaceId == null ? spaces.defaultSpaceForUser(userId).getId() : spaceId;
        spaces.requireOwnedSpace(userId, effective);
        return effective;
    }
}
