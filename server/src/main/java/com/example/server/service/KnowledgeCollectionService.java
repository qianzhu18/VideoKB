package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeCollectionCreateRequest;
import com.example.server.dto.KnowledgeCollectionUpdateRequest;
import com.example.server.dto.KnowledgeCollectionView;
import com.example.server.entity.KnowledgeCollection;
import com.example.server.entity.KnowledgeSpace;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeCollectionMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class KnowledgeCollectionService {

    private static final int MAX_PATH_LENGTH = 700;

    private final KnowledgeCollectionMapper collectionMapper;
    private final KnowledgeSourceMapper sourceMapper;
    private final com.example.server.mapper.KnowledgePlacementMapper placementMapper;
    private final KnowledgeSpaceService spaceService;
    private final KnowledgeAuditService auditService;

    public KnowledgeCollectionService(KnowledgeCollectionMapper collectionMapper,
                                      KnowledgeSourceMapper sourceMapper,
                                      KnowledgeSpaceService spaceService,
                                      KnowledgeAuditService auditService,
                                      com.example.server.mapper.KnowledgePlacementMapper placementMapper) {
        this.collectionMapper = collectionMapper;
        this.sourceMapper = sourceMapper;
        this.spaceService = spaceService;
        this.auditService = auditService;
        this.placementMapper = placementMapper;
    }

    public List<KnowledgeCollectionView> list(Long userId, Long spaceId) {
        spaceService.requireOwnedSpace(userId, spaceId);
        return collectionMapper.selectList(new QueryWrapper<KnowledgeCollection>()
                        .eq("space_id", spaceId))
                .stream()
                .sorted(Comparator.comparing(KnowledgeCollection::getPath)
                        .thenComparing(KnowledgeCollection::getSortOrder,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(KnowledgeCollection::getId))
                .map(KnowledgeCollectionView::from)
                .toList();
    }

    @Transactional
    public KnowledgeCollectionView create(Long userId, Long spaceId, KnowledgeCollectionCreateRequest request) {
        KnowledgeSpace space = spaceService.requireOwnedSpace(userId, spaceId);
        KnowledgeCollection parent = requireParentInSpace(request.parentId(), space.getId());
        String name = normalizeName(request.name());
        String path = childPath(parent, name);
        assertPathAvailable(spaceId, path, null);

        KnowledgeCollection collection = new KnowledgeCollection();
        collection.setSpaceId(space.getId());
        collection.setParentId(parent == null ? null : parent.getId());
        collection.setName(name);
        collection.setPath(path);
        collection.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        try {
            collectionMapper.insert(collection);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一知识空间中已存在该目录路径");
        }
        auditService.record(userId, "COLLECTION_CREATED", "COLLECTION", collection.getId(), collection.getSpaceId(),
                collection.getId(), "path=" + collection.getPath());
        return KnowledgeCollectionView.from(collection);
    }

    @Transactional
    public KnowledgeCollectionView update(Long userId, Long collectionId, KnowledgeCollectionUpdateRequest request) {
        if (request.name() == null && request.parentId() == null
                && !Boolean.TRUE.equals(request.moveToRoot()) && request.sortOrder() == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请至少提供一个需要更新的字段");
        }
        if (request.parentId() != null && Boolean.TRUE.equals(request.moveToRoot())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能同时指定父目录和移动到根目录");
        }

        KnowledgeCollection collection = requireOwnedCollection(userId, collectionId);
        KnowledgeCollection parent = collection.getParentId() == null ? null : requireCollection(collection.getParentId());
        if (request.parentId() != null) {
            parent = requireParentInSpace(request.parentId(), collection.getSpaceId());
        } else if (Boolean.TRUE.equals(request.moveToRoot())) {
            parent = null;
        }
        if (parent != null && isSameOrDescendant(parent.getPath(), collection.getPath())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目录不能移动到自身或其子目录中");
        }

        String name = request.name() == null ? collection.getName() : normalizeName(request.name());
        String newPath = childPath(parent, name);
        assertPathAvailable(collection.getSpaceId(), newPath, collection.getId());

        String oldPath = collection.getPath();
        collection.setParentId(parent == null ? null : parent.getId());
        collection.setName(name);
        collection.setPath(newPath);
        if (request.sortOrder() != null) collection.setSortOrder(request.sortOrder());
        try {
            collectionMapper.updateById(collection);
            if (!oldPath.equals(newPath)) rewriteDescendantPaths(collection.getSpaceId(), oldPath, newPath, collection.getId());
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一知识空间中已存在该目录路径");
        }
        auditService.record(userId, "COLLECTION_UPDATED", "COLLECTION", collection.getId(), collection.getSpaceId(),
                collection.getId(), "from=" + oldPath + ";to=" + collection.getPath());
        return KnowledgeCollectionView.from(collection);
    }

    @Transactional
    public void delete(Long userId, Long collectionId) {
        KnowledgeCollection collection = requireOwnedCollection(userId, collectionId);
        long childCount = collectionMapper.selectCount(new QueryWrapper<KnowledgeCollection>()
                .eq("parent_id", collectionId));
        if (childCount > 0) throw new BusinessException(ErrorCode.CONFLICT, "请先移动或删除子目录");
        long sourceCount = sourceMapper.selectCount(new QueryWrapper<com.example.server.entity.KnowledgeSource>()
                .eq("collection_id", collectionId)
                .ne("status", "DELETED"));
        if (sourceCount > 0) throw new BusinessException(ErrorCode.CONFLICT, "请先移动目录中的内容源");
        long referenceCount = placementMapper.selectCount(
                new QueryWrapper<com.example.server.entity.KnowledgePlacement>().eq("collection_id", collectionId));
        if (referenceCount > 0) throw new BusinessException(ErrorCode.CONFLICT, "请先移除或移动目录中的引用");
        collectionMapper.deleteById(collection.getId());
        auditService.record(userId, "COLLECTION_DELETED", "COLLECTION", collection.getId(), collection.getSpaceId(),
                collection.getId(), "path=" + collection.getPath());
    }

    public KnowledgeCollection requireOwnedCollection(Long userId, Long collectionId) {
        KnowledgeCollection collection = requireCollection(collectionId);
        spaceService.requireOwnedSpace(userId, collection.getSpaceId());
        return collection;
    }

    public KnowledgeCollection requireCollectionInSpace(Long collectionId, Long spaceId) {
        KnowledgeCollection collection = requireCollection(collectionId);
        if (!spaceId.equals(collection.getSpaceId())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目录不属于指定知识空间");
        }
        return collection;
    }

    public List<Long> subtreeIds(Long spaceId, Long collectionId) {
        KnowledgeCollection root = requireCollectionInSpace(collectionId, spaceId);
        return collectionMapper.selectList(new QueryWrapper<KnowledgeCollection>().eq("space_id", spaceId))
                .stream().filter(c -> isSameOrDescendant(c.getPath(), root.getPath()))
                .map(KnowledgeCollection::getId).toList();
    }

    private KnowledgeCollection requireParentInSpace(Long parentId, Long spaceId) {
        return parentId == null ? null : requireCollectionInSpace(parentId, spaceId);
    }

    private KnowledgeCollection requireCollection(Long collectionId) {
        KnowledgeCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null) throw new BusinessException(ErrorCode.NOT_FOUND, "目录不存在");
        return collection;
    }

    private void assertPathAvailable(Long spaceId, String path, Long excludedId) {
        QueryWrapper<KnowledgeCollection> query = new QueryWrapper<KnowledgeCollection>()
                .eq("space_id", spaceId)
                .eq("path", path);
        if (excludedId != null) query.ne("id", excludedId);
        if (collectionMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一知识空间中已存在该目录路径");
        }
    }

    private void rewriteDescendantPaths(Long spaceId, String oldPath, String newPath, Long currentId) {
        List<KnowledgeCollection> descendants = collectionMapper.selectList(new QueryWrapper<KnowledgeCollection>()
                        .eq("space_id", spaceId))
                .stream()
                .filter(item -> !currentId.equals(item.getId()))
                .filter(item -> item.getPath().startsWith(oldPath + "/"))
                .toList();
        for (KnowledgeCollection descendant : descendants) {
            String rewritten = newPath + descendant.getPath().substring(oldPath.length());
            ensurePathLength(rewritten);
            descendant.setPath(rewritten);
            collectionMapper.updateById(descendant);
        }
    }

    private String childPath(KnowledgeCollection parent, String name) {
        String path = parent == null ? "/" + name : parent.getPath() + "/" + name;
        ensurePathLength(path);
        return path;
    }

    private boolean isSameOrDescendant(String candidatePath, String rootPath) {
        return candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + "/");
    }

    private void ensurePathLength(String path) {
        if (path.length() > MAX_PATH_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目录层级过深或路径过长");
        }
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目录名称不能为空");
        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目录名称不能包含路径分隔符");
        }
        return normalized;
    }
}
