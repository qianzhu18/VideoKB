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
}
