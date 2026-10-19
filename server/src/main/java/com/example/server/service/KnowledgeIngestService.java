package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeIngestRequest;
import com.example.server.entity.KnowledgeIngestScan;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeIngestScanMapper;
import com.example.server.utils.MinioUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Local-directory ingest (P3): turns an authorized filesystem folder into knowledge
 * assets. Identity is content (MD5), not path, so moves are metadata-only and only
 * changed content re-enters the knowledge pipeline. The scan plan is always persisted as
 * the import manifest; applying it persists independent knowledge jobs just like
 * manual uploads.
 */
@Service
public class KnowledgeIngestService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(KnowledgeIngestService.class);

    private final KnowledgeSourceService sourceService;
    private final MediaService mediaService;
    private final KnowledgeAuditService auditService;
    private final KnowledgeIngestScanMapper scanMapper;
    private final MinioUtils minioUtils;
    private final List<String> allowedRoots;

    public KnowledgeIngestService(KnowledgeSourceService sourceService,
                                  MediaService mediaService,
                                  KnowledgeAuditService auditService,
                                  KnowledgeIngestScanMapper scanMapper,
                                  MinioUtils minioUtils,
                                  @Value("${knowledge.ingest.allowed-roots:}") String allowedRoots) {
        this.sourceService = sourceService;
        this.mediaService = mediaService;
        this.auditService = auditService;
        this.scanMapper = scanMapper;
        this.minioUtils = minioUtils;
        this.allowedRoots = java.util.Arrays.stream(allowedRoots.split(","))
                .map(String::trim)
                .filter(root -> !root.isEmpty())
                .toList();
    }

    public KnowledgeIngestScan ingest(Long userId, KnowledgeIngestRequest request) {
        Path root = requireAllowedRoot(request.rootPath());
        spaceServiceGuard(userId, request.spaceId(), request.collectionId());

        List<IngestPlanner.DiscoveredFile> discovered = new ArrayList<>();
        Map<String, String> hashErrors = new LinkedHashMap<>();
        discover(root, discovered, hashErrors);
        List<IngestPlanner.KnownAsset> known = knownAssetsUnder(userId, root);

        List<IngestPlanner.Action> actions =
                IngestPlanner.plan(discovered, known, hashErrors);
        boolean dryRun = request.dryRun() == null || request.dryRun();

        List<Map<String, Object>> planView = new ArrayList<>(actions.size());
        if (!dryRun) {
            apply(userId, request, actions, planView);
        } else {
            actions.forEach(action -> planView.add(describe(action, null)));
        }

        return record(userId, root, dryRun, actions, planView);
    }

    public List<KnowledgeIngestScan> history(Long userId) {
        return scanMapper.selectList(new QueryWrapper<KnowledgeIngestScan>()
                .eq("owner_user_id", userId)
                .orderByDesc("id")
                .last("LIMIT 20"));
    }

    private void apply(Long userId, KnowledgeIngestRequest request,
                       List<IngestPlanner.Action> actions,
                       List<Map<String, Object>> planView) {
        for (IngestPlanner.Action action : actions) {
            String error = null;
            try {
                switch (action.type()) {
                    case CREATED -> applyCreated(userId, request, action);
                    case CHANGED -> applyChanged(userId, request, action);
                    case MOVED -> applyMoved(userId, action);
                    case DELETED -> mediaService.deleteOwnedMedia(action.mediaId(), userId);
                    case UNCHANGED, ERROR -> {
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("knowledge_ingest_apply_failed type={} path={} cause={}",
                        action.type(), action.path(), error);
            }
            planView.add(describe(action, error));
        }
    }

    private void applyCreated(Long userId, KnowledgeIngestRequest request,
                              IngestPlanner.Action action) throws Exception {
        File file = Path.of(action.path()).toFile();
        String fileUrl = minioUtils.uploadLocalFile(file, file.getName());
        String md5 = action.md5() != null ? action.md5() : mediaService.calculateMd5(file);
        MediaFile media = mediaService.saveUploadedMedia(file.getName(), fileUrl, userId, md5);
        moveIntoTarget(userId, media.getId(), request);
        sourceService.registerExternalLocation(userId, media.getId(), action.path());
        // The legacy analyze flag no longer requests a report. All accepted media have a
        // durable knowledge job; optional reports are started explicitly in Video Agent.

    }

    private void applyChanged(Long userId, KnowledgeIngestRequest request,
                              IngestPlanner.Action action) throws Exception {
        // Content changed: purge the old asset completely (soft-deleted source, vectors,
        // checkpoints), then bring the new file in as a fresh asset at the same location.
        if (action.mediaId() != null) {
            mediaService.deleteOwnedMedia(action.mediaId(), userId);
        }
        applyCreated(userId, request, action);
    }

    private void applyMoved(Long userId, IngestPlanner.Action action) {
        // Same content at a new path: metadata-only update, no re-analysis.
        sourceService.requireOwnedSource(userId, action.sourceId());
        String title = Path.of(action.path()).getFileName().toString();
        sourceService.renameSource(userId, action.sourceId(), title, action.path());
    }

    private void moveIntoTarget(Long userId, Long mediaId, KnowledgeIngestRequest request) {
        sourceService.attachExistingMedia(userId, mediaId,
                new com.example.server.dto.KnowledgeSourceLocationRequest(
                        request.spaceId(), request.collectionId()));
    }

    private Path requireAllowedRoot(String requested) {
        if (allowedRoots.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "本地导入未启用：请配置 knowledge.ingest.allowed-roots");
        }
        Path root = Paths.get(requested).toAbsolutePath().normalize();
        for (String allowed : allowedRoots) {
            Path allowedPath = Paths.get(allowed).toAbsolutePath().normalize();
            if (IngestPlanner.isUnderRoot(allowedPath, root)) return root;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN,
                "目录不在授权范围内：仅允许 " + allowedRoots);
    }

    private void spaceServiceGuard(Long userId, Long spaceId, Long collectionId) {
        // Fail fast on bad targets before doing any filesystem work.
        sourceService.list(userId, spaceId, collectionId, null);
    }

    private void discover(Path root,
                          List<IngestPlanner.DiscoveredFile> discovered,
                          Map<String, String> hashErrors) {
        if (!Files.isDirectory(root)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目录不存在或不是文件夹");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        String suffix = dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
                        return MediaService.isVideoFile(name);
                    })
                    .forEach(path -> {
                        try {
                            String md5 = mediaService.calculateMd5(path.toFile());
                            discovered.add(new IngestPlanner.DiscoveredFile(
                                    path.toString(), md5));
                        } catch (IOException e) {
                            hashErrors.put(path.toString(),
                                    "无法读取文件: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目录无法遍历: " + e.getMessage());
        }
    }

    private List<IngestPlanner.KnownAsset> knownAssetsUnder(Long userId, Path root) {
        List<IngestPlanner.KnownAsset> known = new ArrayList<>();
        for (KnowledgeSource source : sourceService.listIngested(userId)) {
            String externalPath = source.getExternalPath();
            if (externalPath == null) continue;
            Path path = Paths.get(externalPath).toAbsolutePath().normalize();
            if (!IngestPlanner.isUnderRoot(root, path)) continue;
            MediaFile media = mediaService.requireOwnedMedia(source.getMediaId(), userId);
            known.add(new IngestPlanner.KnownAsset(path.toString(), media.getContentHash(),
                    source.getId(), source.getMediaId()));
        }
        return known;
    }

    private KnowledgeIngestScan record(Long userId, Path root, boolean dryRun,
                                       List<IngestPlanner.Action> actions,
                                       List<Map<String, Object>> planView) {
        KnowledgeIngestScan scan = new KnowledgeIngestScan();
        scan.setOwnerUserId(userId);
        scan.setRootPath(root.toString());
        scan.setDryRun(dryRun);
        scan.setCreatedCount(count(actions, IngestPlanner.ActionType.CREATED));
        scan.setChangedCount(count(actions, IngestPlanner.ActionType.CHANGED));
        scan.setMovedCount(count(actions, IngestPlanner.ActionType.MOVED));
        scan.setDeletedCount(count(actions, IngestPlanner.ActionType.DELETED));
        scan.setUnchangedCount(count(actions, IngestPlanner.ActionType.UNCHANGED));
        scan.setErrorCount(count(actions, IngestPlanner.ActionType.ERROR));
        Map<String, Object> document = new HashMap<>();
        document.put("actions", planView);
        scan.setPlan(JSON.toJSONString(document));
        scanMapper.insert(scan);
        auditService.record(userId, "INGEST_SCANNED", "SPACE", null, null, null,
                "root=" + root + ";dryRun=" + dryRun
                        + ";created=" + scan.getCreatedCount()
                        + ";changed=" + scan.getChangedCount()
                        + ";moved=" + scan.getMovedCount()
                        + ";deleted=" + scan.getDeletedCount());
        return scan;
    }

    private static Map<String, Object> describe(IngestPlanner.Action action, String error) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("action", action.type().name());
        view.put("path", action.path());
        if (action.sourceId() != null) view.put("sourceId", action.sourceId());
        if (action.mediaId() != null) view.put("mediaId", action.mediaId());
        if (error != null) view.put("error", error);
        return view;
    }

    private static int count(List<IngestPlanner.Action> actions, IngestPlanner.ActionType type) {
        return (int) actions.stream().filter(action -> action.type() == type).count();
    }
}
