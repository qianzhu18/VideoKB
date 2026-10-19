package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeSourceLocationRequest;
import com.example.server.dto.KnowledgeSourceTagsRequest;
import com.example.server.dto.KnowledgeSourceView;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.KnowledgeSourceTag;
import com.example.server.entity.KnowledgeSourceVersion;
import com.example.server.entity.KnowledgeSpace;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.mapper.KnowledgeSourceTagMapper;
import com.example.server.mapper.KnowledgeSourceVersionMapper;
import com.example.server.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ownership-checked management of knowledge sources: location moves, tag sets and the
 * soft-delete lifecycle. Every mutation writes a durable audit record; user-owned data
 * is always guarded by {@link #requireOwnedSource}.
 */
@Service
public class KnowledgeSourceService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceService.class);

    public static final String SOURCE_TYPE_VIDEO = "VIDEO";
    /** Uploaded Markdown/plain-text script; indexed into the same retrieval space. */
    public static final String SOURCE_TYPE_SCRIPT = "SCRIPT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELETED = "DELETED";
    /** Set once segments+vectors are queryable; until then the source is not retrievable. */
    public static final String STATUS_READY = "READY";
    /** Index attempt failed; the failure reason lives on the current version row. */
    public static final String STATUS_FAILED = "FAILED";

    static final int MAX_TAGS_PER_SOURCE = 20;
    static final int MAX_TAG_LENGTH = 64;

    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeSourceVersionMapper versionMapper;
    private final KnowledgeSourceTagMapper tagMapper;
    private final MediaFileMapper mediaFileMapper;
    private final KnowledgeSpaceService spaceService;
    private final KnowledgeCollectionService collectionService;
    private final KnowledgeAuditService auditService;
    private final KnowledgePlacementService placementService;
    private final KnowledgeIngestJobService ingestJobs;

    public KnowledgeSourceService(KnowledgeSourceMapper sourceMapper,
                                  KnowledgeSourceVersionMapper versionMapper,
                                  KnowledgeSourceTagMapper tagMapper,
                                  MediaFileMapper mediaFileMapper,
                                  KnowledgeSpaceService spaceService,
                                  KnowledgeCollectionService collectionService,
                                  KnowledgePlacementService placementService,
                                  KnowledgeAuditService auditService,
                                  KnowledgeIngestJobService ingestJobs) {
        this.sourceMapper = sourceMapper;
        this.versionMapper = versionMapper;
        this.tagMapper = tagMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.spaceService = spaceService;
        this.collectionService = collectionService;
        this.placementService = placementService;
        this.auditService = auditService;
        this.ingestJobs = ingestJobs;
    }

    @Transactional
    public KnowledgeSource ensureMediaSource(MediaFile media) {
        KnowledgeSource existing = findByMediaId(media.getId());
        if (existing != null) {
            existing = sourceMapper.lockById(existing.getId());
            placementService.ensurePrimary(existing);
            if (STATUS_PENDING.equals(existing.getStatus())) ingestJobs.enqueue(existing);
            return existing;
        }

        KnowledgeSpace defaultSpace = spaceService.defaultSpaceForUser(media.getUserId());
        KnowledgeSource source = new KnowledgeSource();
        source.setSourceType(SOURCE_TYPE_VIDEO);
        source.setOwnerUserId(media.getUserId());
        source.setSpaceId(defaultSpace.getId());
        source.setMediaId(media.getId());
        source.setTitle(media.getFilename());
        source.setContentHash(media.getContentHash());
        source.setCurrentVersion(1);
        source.setStatus(STATUS_PENDING);
        try {
            sourceMapper.insert(source);
        } catch (DuplicateKeyException error) {
            KnowledgeSource concurrent = findByMediaId(media.getId());
            if (concurrent != null) return concurrent;
            throw error;
        }

        KnowledgeSourceVersion version = new KnowledgeSourceVersion();
        version.setSourceId(source.getId());
        version.setVersionNo(1);
        version.setContentHash(media.getContentHash());
        version.setStatus(STATUS_PENDING);
        versionMapper.insert(version);
        placementService.ensurePrimary(source);
        ingestJobs.enqueue(source);
        auditService.record(media.getUserId(), "SOURCE_CREATED", "SOURCE", source.getId(), source.getSpaceId(), null,
                "type=" + SOURCE_TYPE_VIDEO + ";mediaId=" + media.getId());
        return source;
    }

    @Transactional
    public KnowledgeSourceView attachExistingMedia(Long userId, Long mediaId, KnowledgeSourceLocationRequest request) {
        return attachExistingMedia(userId, mediaId, request, true);
    }

    /** Files imported with a custom goal must be queued exactly once by their caller. */
    @Transactional
    public KnowledgeSourceView attachExistingMediaWithoutAutoDispatch(
            Long userId, Long mediaId, KnowledgeSourceLocationRequest request) {
        return attachExistingMedia(userId, mediaId, request, false);
    }

    private KnowledgeSourceView attachExistingMedia(Long userId, Long mediaId,
                                                     KnowledgeSourceLocationRequest request,
                                                     boolean dispatchPending) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) throw new BusinessException(ErrorCode.NOT_FOUND, "视频不存在");
        if (!userId.equals(media.getUserId())) throw new SecurityException("无权访问该视频");
        KnowledgeSource source = ensureMediaSource(media);
        return move(userId, source.getId(), request, dispatchPending);
    }

    public List<KnowledgeSourceView> list(Long userId, Long spaceId, Long collectionId, String tag) {
        spaceService.requireOwnedSpace(userId, spaceId);
        if (collectionId != null) collectionService.requireCollectionInSpace(collectionId, spaceId);
        List<com.example.server.entity.KnowledgePlacement> locations = placementService.inLocation(
                spaceId, collectionId, collectionId == null && tag != null && !tag.isBlank());
        if (locations.isEmpty()) return List.of();
        Map<Long, com.example.server.entity.KnowledgePlacement> bySource = new java.util.LinkedHashMap<>();
        locations.forEach(p -> bySource.putIfAbsent(p.getSourceId(), p));
        QueryWrapper<KnowledgeSource> query = new QueryWrapper<KnowledgeSource>()
                .eq("owner_user_id", userId).in("id", bySource.keySet())
                .ne("status", STATUS_DELETED).orderByDesc("updated_at");
        if (tag != null && !tag.isBlank()) {
            List<Long> taggedSourceIds = sourceIdsWithTag(tag.trim());
            if (taggedSourceIds.isEmpty()) return List.of();
            query.in("id", taggedSourceIds);
        }
        List<KnowledgeSource> sources = sourceMapper.selectList(query);
        if (sources.isEmpty()) return List.of();
        Map<Long, List<String>> tagsBySource = tagsForSources(sources.stream().map(KnowledgeSource::getId).toList());
        return sources.stream()
                .map(source -> KnowledgeSourceView.from(source, tagsBySource.getOrDefault(source.getId(), List.of()))
                        .at(bySource.get(source.getId())))
                .toList();
    }

    public List<String> listTags(Long userId, Long sourceId) {
        KnowledgeSource source = requireOwnedSource(userId, sourceId);
        return tagsForSources(List.of(source.getId())).getOrDefault(source.getId(), List.of());
    }

    /**
     * Replaces the tag set of a source with differential writes: only removed tags are
     * deleted and only added tags are inserted, so unchanged rows keep their identity
     * and the write volume stays proportional to the actual diff.
     */
    @Transactional
    public KnowledgeSourceView replaceTags(Long userId, Long sourceId, KnowledgeSourceTagsRequest request) {
        KnowledgeSource source = requireOwnedSource(userId, sourceId);
        List<String> normalized = normalizeTags(request.tags());
        Set<String> requested = new LinkedHashSet<>(normalized);
        Set<String> current = new LinkedHashSet<>(
                tagsForSources(List.of(sourceId)).getOrDefault(sourceId, List.of()));
        if (current.equals(requested)) {
            return KnowledgeSourceView.from(source, List.copyOf(requested));
        }
        Set<String> removed = new LinkedHashSet<>(current);
        removed.removeAll(requested);
        Set<String> added = new LinkedHashSet<>(requested);
        added.removeAll(current);
        if (!removed.isEmpty()) {
            tagMapper.delete(new QueryWrapper<KnowledgeSourceTag>()
                    .eq("source_id", sourceId)
                    .in("tag", removed));
        }
        for (String tag : added) {
            KnowledgeSourceTag row = new KnowledgeSourceTag();
            row.setSourceId(sourceId);
            row.setTag(tag);
            try {
                tagMapper.insert(row);
            } catch (DuplicateKeyException error) {
                // A concurrent replace already inserted this tag; the final set is still
                // decided by this transaction, so the duplicate row can simply be skipped.
            }
        }
        auditService.record(userId, "SOURCE_TAGGED", "SOURCE", source.getId(), source.getSpaceId(), source.getCollectionId(),
                "added=" + String.join(",", added) + ";removed=" + String.join(",", removed));
        return KnowledgeSourceView.from(source, normalized);
    }

    static List<String> normalizeTags(List<String> requested) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (requested != null) {
            for (String raw : requested) {
                String tag = raw == null ? "" : raw.trim();
                if (tag.isEmpty()) continue;
                if (tag.length() > MAX_TAG_LENGTH) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "标签不能超过 " + MAX_TAG_LENGTH + " 个字符");
                }
                normalized.add(tag);
            }
        }
        if (normalized.size() > MAX_TAGS_PER_SOURCE) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "每个内容源最多 " + MAX_TAGS_PER_SOURCE + " 个标签");
        }
        return new ArrayList<>(normalized);
    }

    private List<Long> sourceIdsWithTag(String tag) {
        return tagMapper.selectList(new QueryWrapper<KnowledgeSourceTag>().eq("tag", tag))
                .stream().map(KnowledgeSourceTag::getSourceId).distinct().toList();
    }

    private Map<Long, List<String>> tagsForSources(List<Long> sourceIds) {
        if (sourceIds.isEmpty()) return Map.of();
        return tagMapper.selectList(new QueryWrapper<KnowledgeSourceTag>().in("source_id", sourceIds).orderByAsc("id"))
                .stream()
                .collect(Collectors.groupingBy(KnowledgeSourceTag::getSourceId,
                        Collectors.mapping(KnowledgeSourceTag::getTag, Collectors.toList())));
    }

    @Transactional
    public KnowledgeSourceView move(Long userId, Long sourceId, KnowledgeSourceLocationRequest request) {
        return move(userId, sourceId, request, true);
    }

    private KnowledgeSourceView move(Long userId, Long sourceId,
                                     KnowledgeSourceLocationRequest request,
                                     boolean dispatchPending) {
        KnowledgeSource source = requireOwnedSource(userId, sourceId);
        Long previousSpaceId = source.getSpaceId();
        Long previousCollectionId = source.getCollectionId();
        var placement = placementService.movePrimary(userId, source, request);
        source.setSpaceId(placement.getSpaceId());
        source.setCollectionId(placement.getCollectionId());
        if (dispatchPending) dispatchIfPending(source);
        auditService.record(userId, "SOURCE_MOVED", "SOURCE", source.getId(), source.getSpaceId(), source.getCollectionId(),
                "fromSpace=" + previousSpaceId + ";fromCollection=" + previousCollectionId);
        return KnowledgeSourceView.from(source).at(placement);
    }

    /** Filing a pending source ensures a durable knowledge job; no report is requested. */
    private void dispatchIfPending(KnowledgeSource source) {
        if (!STATUS_PENDING.equals(source.getStatus())) return;
        dispatchPending(source, "filing");
    }

    /** Recover durable knowledge jobs without spending an Agent report budget. */
    public int dispatchPendingInSpace(Long userId, Long spaceId) {
        spaceService.requireOwnedSpace(userId, spaceId);
        var ids = placementService.inLocation(spaceId, null, true).stream()
                .map(com.example.server.entity.KnowledgePlacement::getSourceId).distinct().toList();
        if (ids.isEmpty()) return 0;
        var pending = sourceMapper.selectList(new QueryWrapper<KnowledgeSource>()
                .eq("owner_user_id", userId).in("id", ids).eq("status", STATUS_PENDING));
        int count = 0;
        for (KnowledgeSource source : pending) if (dispatchPending(source, "space-catch-up")) count++;
        return count;
    }

    private boolean dispatchPending(KnowledgeSource source, String trigger) {
        return source.getMediaId() != null && ingestJobs.enqueue(source);
    }

    @Transactional
    public void markMediaDeleted(Long userId, Long mediaId) {
        KnowledgeSource source = sourceMapper.selectOne(new QueryWrapper<KnowledgeSource>()
                .eq("owner_user_id", userId)
                .eq("media_id", mediaId));
        if (source == null || STATUS_DELETED.equals(source.getStatus())) return;
        ingestJobs.cancel(source.getId());
        placementService.removeAll(source.getId());
        source.setStatus(STATUS_DELETED);
        sourceMapper.updateById(source);
        auditService.record(userId, "SOURCE_DELETED", "SOURCE", source.getId(), source.getSpaceId(), source.getCollectionId(),
                "mediaId=" + mediaId);
    }

    /**
     * Reflects an index outcome on the source row so list views show real readiness
     * instead of a PENDING that never clears. Called by the index service only.
     */
    public void updateIndexStatus(KnowledgeSource source, String status) {
        if (STATUS_DELETED.equals(source.getStatus())) return;
        source.setStatus(status);
        sourceMapper.update(null, new UpdateWrapper<KnowledgeSource>().eq("id", source.getId())
                .ne("status", STATUS_DELETED).set("status", status));
    }

    /** Ownership guard shared with the index and search services; throws for missing or foreign sources. */
    public KnowledgeSource requireOwnedSource(Long userId, Long sourceId) {
        KnowledgeSource source = sourceMapper.selectById(sourceId);
        if (source == null || STATUS_DELETED.equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "内容源不存在");
        }
        if (!userId.equals(source.getOwnerUserId())) throw new SecurityException("无权访问该内容源");
        return source;
    }

    public KnowledgeSource requireSourceByMediaId(Long mediaId) {
        KnowledgeSource source = findByMediaId(mediaId);
        if (source == null || STATUS_DELETED.equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "内容源不存在");
        }
        return source;
    }

    /** All non-deleted sources of a user that were ingested from a local path. */
    public List<KnowledgeSource> listIngested(Long userId) {
        return sourceMapper.selectList(new QueryWrapper<KnowledgeSource>()
                .eq("owner_user_id", userId)
                .ne("status", STATUS_DELETED)
                .isNotNull("external_path"));
    }

    /** Records the local file location of an ingested source (path is metadata, never identity). */
    public void registerExternalLocation(Long userId, Long mediaId, String externalPath) {
        KnowledgeSource source = requireSourceByMediaId(mediaId);
        if (!userId.equals(source.getOwnerUserId())) throw new SecurityException("无权访问该内容源");
        source.setExternalPath(externalPath);
        sourceMapper.updateById(source);
        auditService.record(userId, "SOURCE_PATH_UPDATED", "SOURCE", source.getId(),
                source.getSpaceId(), source.getCollectionId(),
                "externalPath=" + externalPath);
    }

    /** Metadata-only rename for an ingested source whose file moved on disk. */
    public void renameSource(Long userId, Long sourceId, String title, String externalPath) {
        KnowledgeSource source = requireOwnedSource(userId, sourceId);
        source.setTitle(title);
        source.setExternalPath(externalPath);
        sourceMapper.updateById(source);
        auditService.record(userId, "SOURCE_PATH_UPDATED", "SOURCE", source.getId(),
                source.getSpaceId(), source.getCollectionId(),
                "title=" + title + ";externalPath=" + externalPath);
    }

    private KnowledgeSource findByMediaId(Long mediaId) {
        return sourceMapper.selectOne(new QueryWrapper<KnowledgeSource>().eq("media_id", mediaId));
    }
}
