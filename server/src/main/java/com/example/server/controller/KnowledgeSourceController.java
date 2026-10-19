package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.KnowledgeSegmentView;
import com.example.server.dto.KnowledgeSourceLocationRequest;
import com.example.server.dto.KnowledgeSourceTagsRequest;
import com.example.server.dto.KnowledgeSourceView;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeSegmentIndexService;
import com.example.server.service.KnowledgeSourceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/knowledge/sources")
public class KnowledgeSourceController {

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSegmentIndexService segmentIndexService;
    private final com.example.server.service.KnowledgePlacementService placements;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.server.service.KnowledgeLexicalIndexService lexicalIndex;

    public KnowledgeSourceController(KnowledgeSourceService sourceService,
                                     KnowledgeSegmentIndexService segmentIndexService,
                                     com.example.server.service.KnowledgePlacementService placements) {
        this.sourceService = sourceService;
        this.segmentIndexService = segmentIndexService;
        this.placements = placements;
    }

    @GetMapping
    public Result<List<KnowledgeSourceView>> list(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @RequestParam Long spaceId,
            @RequestParam(required = false) Long collectionId,
            @RequestParam(required = false) String tag) {
        return Result.ok(sourceService.list(userId, spaceId, collectionId, tag));
    }

    @PostMapping("/media/{mediaId}")
    public Result<KnowledgeSourceView> attachExistingMedia(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long mediaId,
            @Valid @RequestBody KnowledgeSourceLocationRequest request) {
        return Result.ok(sourceService.attachExistingMedia(userId, mediaId, request));
    }

    @PatchMapping("/{sourceId}/location")
    public Result<KnowledgeSourceView> move(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long sourceId,
            @Valid @RequestBody KnowledgeSourceLocationRequest request) {
        return Result.ok(sourceService.move(userId, sourceId, request));
    }

    @GetMapping("/{sourceId}/placements")
    public Result<List<com.example.server.entity.KnowledgePlacement>> placements(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId, @PathVariable Long sourceId) {
        return Result.ok(placements.listOwned(userId, sourceId));
    }

    @PostMapping("/{sourceId}/placements")
    public Result<com.example.server.entity.KnowledgePlacement> addPlacement(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId, @PathVariable Long sourceId,
            @Valid @RequestBody KnowledgeSourceLocationRequest request) {
        return Result.ok(placements.add(userId, sourceId, request));
    }

    @PatchMapping("/{sourceId}/placements/{placementId}")
    public Result<com.example.server.entity.KnowledgePlacement> movePlacement(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId, @PathVariable Long sourceId,
            @PathVariable Long placementId, @Valid @RequestBody KnowledgeSourceLocationRequest request) {
        return Result.ok(placements.move(userId, sourceId, placementId, request));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{sourceId}/placements/{placementId}")
    public Result<Void> removePlacement(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId, @PathVariable Long sourceId,
            @PathVariable Long placementId) {
        placements.remove(userId, sourceId, placementId);
        return Result.ok();
    }

    @GetMapping("/{sourceId}/tags")
    public Result<List<String>> tags(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long sourceId) {
        return Result.ok(sourceService.listTags(userId, sourceId));
    }

    @PutMapping("/{sourceId}/tags")
    public Result<KnowledgeSourceView> replaceTags(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long sourceId,
            @Valid @RequestBody KnowledgeSourceTagsRequest request) {
        return Result.ok(sourceService.replaceTags(userId, sourceId, request));
    }

    /** Owner-checked, lexical-only backfill of the current published generation. */
    @PostMapping("/{sourceId}/lexical-index")
    public Result<Integer> backfillLexical(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId, @PathVariable Long sourceId) {
        return Result.ok(lexicalIndex.backfill(userId, sourceId));
    }

    /** Manual rebuild of segments and vectors; also the migration path for pre-P2 media. */
    @PostMapping("/{sourceId}/reindex")
    public Result<Integer> reindex(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long sourceId) {
        return Result.ok(segmentIndexService.indexSource(userId, sourceId).size());
    }

    /**
     * Read-only evidence rows of one media (ASR/OCR/summary per time window).
     * This is the surface the MCP adapter's get_video_evidence tool consumes.
     */
    @GetMapping("/media/{mediaId}/segments")
    public Result<List<KnowledgeSegmentView>> segments(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long mediaId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long versionId) {
        return Result.ok(segmentIndexService.listSegments(userId, mediaId, versionId).stream()
                .map(KnowledgeSegmentView::from)
                .toList());
    }
}
