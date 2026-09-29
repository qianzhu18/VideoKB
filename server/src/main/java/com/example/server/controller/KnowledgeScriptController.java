package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.KnowledgeLinkView;
import com.example.server.dto.ScriptSourceRequest;
import com.example.server.dto.KnowledgeSourceView;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeLinkService;
import com.example.server.service.KnowledgeScriptService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Script upload (P4) and script↔video link lifecycle. Suggestions are machine
 * opinions; confirm/reject is the only path to a factual relation.
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeScriptController {

    private final KnowledgeScriptService scriptService;
    private final KnowledgeLinkService linkService;

    public KnowledgeScriptController(KnowledgeScriptService scriptService,
                                     KnowledgeLinkService linkService) {
        this.scriptService = scriptService;
        this.linkService = linkService;
    }

    @PostMapping("/sources/script")
    public Result<KnowledgeSourceView> createScript(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody ScriptSourceRequest request) {
        return Result.ok(scriptService.createScript(userId, request));
    }

    @PostMapping("/sources/{sourceId}/links/suggest")
    public Result<Integer> suggestLinks(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long sourceId) {
        return Result.ok(linkService.suggest(userId, sourceId));
    }

    @GetMapping("/sources/{sourceId}/links")
    public Result<List<KnowledgeLinkView>> listLinks(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long sourceId,
            @RequestParam(required = false) String status) {
        return Result.ok(linkService.list(userId, sourceId, status));
    }

    @PostMapping("/links/{linkId}/confirm")
    public Result<Void> confirmLink(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long linkId) {
        linkService.confirm(userId, linkId);
        return Result.ok(null);
    }

    @PostMapping("/links/{linkId}/reject")
    public Result<Void> rejectLink(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long linkId) {
        linkService.reject(userId, linkId);
        return Result.ok(null);
    }
}
