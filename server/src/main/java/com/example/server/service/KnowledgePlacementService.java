package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeSourceLocationRequest;
import com.example.server.entity.KnowledgePlacement;
import com.example.server.entity.KnowledgeSource;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgePlacementMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Catalog operations never copy content, transcribe video or change vectors. */
@Service
public class KnowledgePlacementService {
    private final KnowledgePlacementMapper placements;
    private final KnowledgeSourceMapper sources;
    private final KnowledgeSpaceService spaces;
    private final KnowledgeCollectionService collections;
    private final KnowledgeAuditService audit;

    public KnowledgePlacementService(KnowledgePlacementMapper placements, KnowledgeSourceMapper sources,
                                     KnowledgeSpaceService spaces, KnowledgeCollectionService collections,
                                     KnowledgeAuditService audit) {
        this.placements = placements;
        this.sources = sources;
        this.spaces = spaces;
        this.collections = collections;
        this.audit = audit;
    }

    public KnowledgePlacement ensurePrimary(KnowledgeSource source) {
        return insertIfAbsent(source.getId(), source.getSpaceId(), source.getCollectionId());
    }

    @Transactional
    public KnowledgePlacement add(Long userId, Long sourceId, KnowledgeSourceLocationRequest location) {
        owned(userId, sourceId);
        validate(userId, location);
        KnowledgePlacement placement = insertIfAbsent(sourceId, location.spaceId(), location.collectionId());
        audit.record(userId, "SOURCE_REFERENCED", "SOURCE", sourceId, location.spaceId(), location.collectionId(),
                "placementId=" + placement.getId());
        return placement;
    }

    @Transactional
    public KnowledgePlacement move(Long userId, Long sourceId, Long placementId,
                                    KnowledgeSourceLocationRequest location) {
        KnowledgeSource source = owned(userId, sourceId);
        KnowledgePlacement origin = placements.selectById(placementId);
        if (origin == null || !sourceId.equals(origin.getSourceId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目录归属不存在");
        }
        validate(userId, location);
        if (same(origin, location.spaceId(), location.collectionId())) return origin;
        KnowledgePlacement target = insertIfAbsent(sourceId, location.spaceId(), location.collectionId());
        placements.deleteById(origin.getId());
        if (same(origin, source.getSpaceId(), source.getCollectionId())) projectPrimary(source, target);
        audit.record(userId, "SOURCE_PLACEMENT_MOVED", "SOURCE", sourceId, target.getSpaceId(), target.getCollectionId(),
                "fromPlacement=" + placementId);
        return target;
    }

    @Transactional
    public void remove(Long userId, Long sourceId, Long placementId) {
        KnowledgeSource source = owned(userId, sourceId);
        KnowledgePlacement origin = placements.selectById(placementId);
        if (origin == null || !sourceId.equals(origin.getSourceId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目录归属不存在");
        }
        List<KnowledgePlacement> remaining = list(sourceId).stream()
                .filter(p -> !p.getId().equals(placementId)).toList();
        if (remaining.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "请保留至少一个目录归属；删除视频请使用视频删除功能");
        placements.deleteById(placementId);
        if (same(origin, source.getSpaceId(), source.getCollectionId())) projectPrimary(source, remaining.getFirst());
        audit.record(userId, "SOURCE_REFERENCE_REMOVED", "SOURCE", sourceId, origin.getSpaceId(), origin.getCollectionId(),
                "placementId=" + placementId);
    }

    public void removeAll(Long sourceId) {
        placements.delete(new QueryWrapper<KnowledgePlacement>().eq("source_id", sourceId));
    }

    public List<KnowledgePlacement> listOwned(Long userId, Long sourceId) {
        owned(userId, sourceId);
        return list(sourceId);
    }

    public List<KnowledgePlacement> inLocation(Long spaceId, Long collectionId, boolean spaceWide) {
        QueryWrapper<KnowledgePlacement> query = new QueryWrapper<KnowledgePlacement>().eq("space_id", spaceId);
        if (!spaceWide) {
            if (collectionId == null) query.isNull("collection_id");
            else query.eq("collection_id", collectionId);
        }
        return placements.selectList(query.orderByAsc("id"));
    }

    @Transactional
    public KnowledgePlacement movePrimary(Long userId, KnowledgeSource source, KnowledgeSourceLocationRequest location) {
        KnowledgePlacement primary = ensurePrimary(owned(userId, source.getId()));
        return move(userId, source.getId(), primary.getId(), location);
    }

    private List<KnowledgePlacement> list(Long sourceId) {
        return placements.selectList(new QueryWrapper<KnowledgePlacement>().eq("source_id", sourceId).orderByAsc("id"));
    }

    private KnowledgePlacement insertIfAbsent(Long sourceId, Long spaceId, Long collectionId) {
        QueryWrapper<KnowledgePlacement> query = new QueryWrapper<KnowledgePlacement>()
                .eq("source_id", sourceId).eq("space_id", spaceId);
        if (collectionId == null) query.isNull("collection_id"); else query.eq("collection_id", collectionId);
        KnowledgePlacement existing = placements.selectOne(query);
        if (existing != null) return existing;
        KnowledgePlacement row = new KnowledgePlacement();
        row.setSourceId(sourceId); row.setSpaceId(spaceId); row.setCollectionId(collectionId);
        try { placements.insert(row); }
        catch (DuplicateKeyException e) {
            existing = placements.selectOne(query);
            if (existing == null) throw e;
            return existing;
        }
        return row;
    }

    private KnowledgeSource owned(Long userId, Long sourceId) {
        // Serialize membership mutations, including concurrent unlink of the last two references.
        KnowledgeSource source = sources.lockById(sourceId);
        if (source == null || KnowledgeSourceService.STATUS_DELETED.equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "内容源不存在");
        }
        if (!userId.equals(source.getOwnerUserId())) throw new SecurityException("无权访问该内容源");
        return source;
    }

    private void validate(Long userId, KnowledgeSourceLocationRequest location) {
        spaces.requireOwnedSpace(userId, location.spaceId());
        if (location.collectionId() != null) collections.requireCollectionInSpace(location.collectionId(), location.spaceId());
    }

    private boolean same(KnowledgePlacement p, Long spaceId, Long collectionId) {
        return Objects.equals(p.getSpaceId(), spaceId) && Objects.equals(p.getCollectionId(), collectionId);
    }

    private void projectPrimary(KnowledgeSource source, KnowledgePlacement placement) {
        sources.update(null, new UpdateWrapper<KnowledgeSource>().eq("id", source.getId())
                .set("space_id", placement.getSpaceId()).set("collection_id", placement.getCollectionId()));
        source.setSpaceId(placement.getSpaceId()); source.setCollectionId(placement.getCollectionId());
    }
}
