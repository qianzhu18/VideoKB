package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.common.Result;
import com.example.server.dto.*;
import com.example.server.entity.KnowledgeSource;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Directory discovery and processing status use the same owner boundary as queries. */
@RestController
public class KnowledgeCatalogController {
    private final KnowledgeSpaceService spaces;
    private final KnowledgeCollectionService folders;
    private final KnowledgePlacementService placements;
    private final KnowledgeSourceMapper sources;
    private final KnowledgeIngestJobService jobs;
    public KnowledgeCatalogController(KnowledgeSpaceService spaces, KnowledgeCollectionService folders,
            KnowledgePlacementService placements, KnowledgeSourceMapper sources, KnowledgeIngestJobService jobs) {
        this.spaces = spaces; this.folders = folders; this.placements = placements; this.sources = sources; this.jobs = jobs;
    }
    @GetMapping("/knowledge/spaces/{spaceId}/catalog")
    public Result<Map<String, Object>> catalog(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
                                             @PathVariable Long spaceId) {
        var space = spaces.requireOwnedSpace(userId, spaceId);
        var locations = placements.inLocation(spaceId, null, true);
        var ids = locations.stream().map(p -> p.getSourceId()).distinct().toList();
        List<KnowledgeSource> visible = ids.isEmpty() ? List.of() : sources.selectList(new QueryWrapper<KnowledgeSource>()
                .eq("owner_user_id", userId).in("id", ids).ne("status", "DELETED"));
        Set<Long> visibleIds = visible.stream().map(KnowledgeSource::getId).collect(Collectors.toSet());
        return Result.ok(Map.of("space", KnowledgeSpaceView.from(space), "collections", folders.list(userId, spaceId),
                "sources", visible.stream().map(KnowledgeSourceView::from).toList(),
                "placements", locations.stream().filter(p -> visibleIds.contains(p.getSourceId())).toList(),
                "ingestJobs", jobs.list(userId).stream().filter(j -> visibleIds.contains(j.getSourceId())).toList()));
    }
}
