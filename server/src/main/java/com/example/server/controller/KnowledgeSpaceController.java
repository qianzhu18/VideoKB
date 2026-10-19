package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.KnowledgeSpaceCreateRequest;
import com.example.server.dto.KnowledgeSpaceUpdateRequest;
import com.example.server.dto.KnowledgeSpaceView;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeSourceService;
import com.example.server.service.KnowledgeSpaceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge/spaces")
public class KnowledgeSpaceController {

    private final KnowledgeSpaceService knowledgeSpaceService;
    private final KnowledgeSourceService knowledgeSourceService;

    public KnowledgeSpaceController(KnowledgeSpaceService knowledgeSpaceService,
                                    KnowledgeSourceService knowledgeSourceService) {
        this.knowledgeSpaceService = knowledgeSpaceService;
        this.knowledgeSourceService = knowledgeSourceService;
    }

    @GetMapping
    public Result<List<KnowledgeSpaceView>> list(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(knowledgeSpaceService.listOwnedSpaces(userId));
    }

    @PostMapping
    public Result<KnowledgeSpaceView> create(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody KnowledgeSpaceCreateRequest request) {
        return Result.ok(knowledgeSpaceService.create(userId, request));
    }

    @PatchMapping("/{spaceId}")
    public Result<KnowledgeSpaceView> update(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long spaceId,
            @Valid @RequestBody KnowledgeSpaceUpdateRequest request) {
        return Result.ok(knowledgeSpaceService.update(userId, spaceId, request));
    }

    /**
     * Batch catch-up: starts the default analysis for every still-PENDING source in the
     * space (assets filed before auto-dispatch existed, or whose dispatch failed).
     * Returns how many tasks were newly submitted.
     */
    @PostMapping("/{spaceId}/analyze-pending")
    public Result<Map<String, Object>> analyzePending(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long spaceId) {
        int dispatched = knowledgeSourceService.dispatchPendingInSpace(userId, spaceId);
        return Result.ok(Map.of("dispatched", dispatched));
    }
}
