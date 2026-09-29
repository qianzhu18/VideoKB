package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeSpaceCreateRequest;
import com.example.server.dto.KnowledgeSpaceUpdateRequest;
import com.example.server.dto.KnowledgeSpaceView;
import com.example.server.entity.KnowledgeSpace;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeSpaceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class KnowledgeSpaceService {

    public static final String DEFAULT_SPACE_NAME = "未分类";
    private static final String DEFAULT_SPACE_DESCRIPTION = "系统默认知识空间";

    private final KnowledgeSpaceMapper knowledgeSpaceMapper;
    private final KnowledgeAuditService auditService;

    public KnowledgeSpaceService(KnowledgeSpaceMapper knowledgeSpaceMapper,
                                 KnowledgeAuditService auditService) {
        this.knowledgeSpaceMapper = knowledgeSpaceMapper;
        this.auditService = auditService;
    }

    public List<KnowledgeSpaceView> listOwnedSpaces(Long userId) {
        defaultSpaceForUser(userId);
        return knowledgeSpaceMapper.selectList(new QueryWrapper<KnowledgeSpace>()
                        .eq("owner_user_id", userId))
                .stream()
                .sorted(Comparator.comparing(KnowledgeSpace::getSystemDefault,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(KnowledgeSpace::getId, Comparator.reverseOrder()))
                .map(KnowledgeSpaceView::from)
                .toList();
    }

    public KnowledgeSpaceView create(Long userId, KnowledgeSpaceCreateRequest request) {
        defaultSpaceForUser(userId);
        String name = normalizeName(request.name());
        if (existsWithName(userId, name, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已存在同名知识空间");
        }

        KnowledgeSpace space = new KnowledgeSpace();
        space.setOwnerUserId(userId);
        space.setName(name);
        space.setDescription(normalizeDescription(request.description()));
        space.setSystemDefault(false);
        try {
            knowledgeSpaceMapper.insert(space);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.CONFLICT, "已存在同名知识空间");
        }
        auditService.record(userId, "SPACE_CREATED", "SPACE", space.getId(), space.getId(), null,
                "name=" + space.getName());
        return KnowledgeSpaceView.from(space);
    }

    public KnowledgeSpaceView update(Long userId, Long spaceId, KnowledgeSpaceUpdateRequest request) {
        if (request.name() == null && request.description() == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请至少提供一个需要更新的字段");
        }

        KnowledgeSpace space = requireOwnedSpace(userId, spaceId);
        if (request.name() != null) {
            String name = normalizeName(request.name());
            if (Boolean.TRUE.equals(space.getSystemDefault()) && !DEFAULT_SPACE_NAME.equals(name)) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "默认知识空间名称不可修改");
            }
            if (existsWithName(userId, name, spaceId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "已存在同名知识空间");
            }
            space.setName(name);
        }
        if (request.description() != null) {
            space.setDescription(normalizeDescription(request.description()));
        }
        try {
            knowledgeSpaceMapper.updateById(space);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.CONFLICT, "已存在同名知识空间");
        }
        auditService.record(userId, "SPACE_UPDATED", "SPACE", space.getId(), space.getId(), null,
                "name=" + space.getName());
        return KnowledgeSpaceView.from(space);
    }

    /** Ensures a stable landing space for newly uploaded videos and migrated media. */
    public KnowledgeSpace defaultSpaceForUser(Long userId) {
        KnowledgeSpace existing = findDefaultSpace(userId);
        if (existing != null) return existing;

        KnowledgeSpace candidate = new KnowledgeSpace();
        candidate.setOwnerUserId(userId);
        candidate.setName(DEFAULT_SPACE_NAME);
        candidate.setDescription(DEFAULT_SPACE_DESCRIPTION);
        candidate.setSystemDefault(true);
        try {
            knowledgeSpaceMapper.insert(candidate);
            auditService.record(userId, "SPACE_DEFAULT_CREATED", "SPACE", candidate.getId(), candidate.getId(), null,
                    "name=" + DEFAULT_SPACE_NAME);
            return candidate;
        } catch (DuplicateKeyException error) {
            KnowledgeSpace concurrent = findDefaultSpace(userId);
            if (concurrent != null) return concurrent;
            throw error;
        }
    }

    public KnowledgeSpace requireOwnedSpace(Long userId, Long spaceId) {
        KnowledgeSpace space = knowledgeSpaceMapper.selectById(spaceId);
        if (space == null) throw new BusinessException(ErrorCode.NOT_FOUND, "知识空间不存在");
        if (!userId.equals(space.getOwnerUserId())) throw new SecurityException("无权访问该知识空间");
        return space;
    }

    private KnowledgeSpace findDefaultSpace(Long userId) {
        return knowledgeSpaceMapper.selectOne(new QueryWrapper<KnowledgeSpace>()
                .eq("owner_user_id", userId)
                .eq("is_system_default", true));
    }

    private boolean existsWithName(Long userId, String name, Long excludedId) {
        QueryWrapper<KnowledgeSpace> query = new QueryWrapper<KnowledgeSpace>()
                .eq("owner_user_id", userId)
                .eq("name", name);
        if (excludedId != null) query.ne("id", excludedId);
        return knowledgeSpaceMapper.selectCount(query) > 0;
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "知识空间名称不能为空");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }
}
