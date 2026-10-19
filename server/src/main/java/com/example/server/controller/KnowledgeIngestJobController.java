package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.entity.KnowledgeIngestJob;
import com.example.server.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/knowledge/ingest-jobs")
public class KnowledgeIngestJobController {
    private final KnowledgeIngestJobService jobs;
    private final KnowledgeSourceService sources;
    public KnowledgeIngestJobController(KnowledgeIngestJobService jobs, KnowledgeSourceService sources) {
        this.jobs = jobs; this.sources = sources;
    }
    @GetMapping
    public Result<List<KnowledgeIngestJob>> list(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(jobs.list(userId));
    }
    @PostMapping("/sources/{sourceId}/retry")
    public Result<Boolean> retry(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId, @PathVariable Long sourceId) {
        var source = sources.requireOwnedSource(userId, sourceId);
        return Result.ok(jobs.retry(userId, sourceId) || jobs.enqueue(source));
    }
}
