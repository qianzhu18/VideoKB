package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.KnowledgeSearchHit;
import com.example.server.dto.KnowledgeSearchRequest;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeSearchController {

    private final KnowledgeSearchService searchService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.server.service.KnowledgeQueryStateService queryStates;

    public KnowledgeSearchController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    /** Cross-video evidence search; an empty list means no supporting evidence was found. */
    @PostMapping("/search")
    public Result<List<KnowledgeSearchHit>> search(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody KnowledgeSearchRequest request) {
        return Result.ok(searchService.search(userId, request));
    }

    /** Additive contract: legacy /search remains an array for older clients. */
    @PostMapping("/search/details")
    public Result<java.util.Map<String, Object>> details(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody KnowledgeSearchRequest request) {
        var state = queryStates.describe(userId, request.spaceId(), request.collectionId());
        var result = searchService.searchWithDiagnostics(userId, request);
        var warnings = new java.util.ArrayList<>(result.warnings());
        if (!state.warning().isEmpty()) warnings.add(state.warning());
        return Result.ok(java.util.Map.of("scope", state, "hits", result.hits(), "warnings", warnings));
    }
}
