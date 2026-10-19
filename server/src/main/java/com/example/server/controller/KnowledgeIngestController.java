package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.KnowledgeIngestRequest;
import com.example.server.dto.TranscriptImportRequest;
import com.example.server.entity.KnowledgeIngestScan;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeIngestService;
import com.example.server.service.KnowledgeSeedService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/knowledge/ingest")
public class KnowledgeIngestController {

    private final KnowledgeIngestService ingestService;
    private final KnowledgeSeedService seedService;

    public KnowledgeIngestController(KnowledgeIngestService ingestService,
                                     KnowledgeSeedService seedService) {
        this.ingestService = ingestService;
        this.seedService = seedService;
    }

    /**
     * Scans an authorized local directory and diffs it against ingested assets. Dry run
     * (default) returns the plan only; apply executes it — new or changed content enters
     * the normal async analysis pipeline (unless analyze=false), moves are metadata-only,
     * deletions are soft.
     */
    @PostMapping("/scan")
    public Result<KnowledgeIngestScan> scan(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody KnowledgeIngestRequest request) {
        return Result.ok(ingestService.ingest(userId, request));
    }

    /** Zero-reburn import of an externally produced transcript into a registered media. */
    @PostMapping("/import-transcript")
    public Result<KnowledgeSeedService.ImportResult> importTranscript(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody TranscriptImportRequest request) {
        return Result.ok(seedService.importTranscript(userId, request));
    }

    /** Recent import manifests (the scan history / import ledger). */
    @GetMapping("/scans")
    public Result<List<KnowledgeIngestScan>> history(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(ingestService.history(userId));
    }
}
