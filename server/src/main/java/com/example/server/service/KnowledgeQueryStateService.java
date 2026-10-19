package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.KnowledgeQueryState;
import com.example.server.entity.KnowledgeSource;
import com.example.server.mapper.KnowledgeSourceMapper;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeQueryStateService {
    private final KnowledgeScopeResolver scopes;
    private final KnowledgePlacementService placements;
    private final KnowledgeSourceMapper sources;
    private final KnowledgeCollectionService collections;
    public KnowledgeQueryStateService(KnowledgeScopeResolver scopes, KnowledgePlacementService placements,
                                      KnowledgeSourceMapper sources, KnowledgeCollectionService collections) {
        this.scopes = scopes; this.placements = placements; this.sources = sources; this.collections = collections;
    }
    public KnowledgeQueryState describe(Long userId, Long spaceId, Long collectionId) {
        Long effective = scopes.effectiveSpace(userId, spaceId, collectionId);
        var scope = scopes.resolve(userId, effective, collectionId);
        var subtree = collectionId == null ? java.util.List.<Long>of() : collections.subtreeIds(effective, collectionId);
        var ids = placements.inLocation(effective, collectionId, true).stream()
                .filter(p -> collectionId == null || subtree.contains(p.getCollectionId()))
                .map(p -> p.getSourceId()).distinct().toList();
        var rows = ids.isEmpty() ? java.util.List.<KnowledgeSource>of() : sources.selectList(
                new QueryWrapper<KnowledgeSource>().eq("owner_user_id", userId).in("id", ids).ne("status", "DELETED"));
        int ready = 0, pending = 0, failed = 0;
        for (var row : rows) {
            if (scope.generations().containsKey(row.getId())) ready++;
            else if ("FAILED".equals(row.getStatus())) failed++;
            else pending++;
        }
        String state = ready > 0 ? (pending + failed > 0 ? "PARTIAL" : "READY")
                : pending > 0 ? "NOT_READY" : failed > 0 ? "FAILED" : "EMPTY";
        return new KnowledgeQueryState(effective, collectionId, state, ready, pending, failed);
    }
}
