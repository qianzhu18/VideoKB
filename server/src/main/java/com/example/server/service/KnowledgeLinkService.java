package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeLinkView;
import com.example.server.entity.KnowledgeLink;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeLinkMapper;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Script-segment ↔ video-segment relations. Suggestions are pure vector similarity —
 * deterministic, cheap, auditable — and land as SUGGESTED, which is an opinion, not a
 * fact: only explicit confirm() turns a link into CONFIRMED.
 */
@Service
public class KnowledgeLinkService {

    public static final String LINK_TYPE_SEGMENT_MATCH = "SEGMENT_MATCH";
    public static final String STATUS_SUGGESTED = "SUGGESTED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** Video matches kept per script paragraph per suggest run. */
    static final int MATCHES_PER_PARAGRAPH = 2;
    /** Script paragraphs processed per suggest run; keeps runs bounded on huge scripts. */
    static final int MAX_PARAGRAPHS_PER_RUN = 40;

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final KnowledgeLinkMapper linkMapper;
    private final KnowledgeSearchService searchService;
    private final KnowledgeAuditService auditService;
    private final double minSuggestScore;

    public KnowledgeLinkService(KnowledgeSourceService sourceService,
                                KnowledgeSourceMapper sourceMapper,
                                KnowledgeSegmentMapper segmentMapper,
                                KnowledgeLinkMapper linkMapper,
                                KnowledgeSearchService searchService,
                                KnowledgeAuditService auditService,
                                @Value("${knowledge.link.suggest-min-score:0.50}") double minSuggestScore) {
        this.sourceService = sourceService;
        this.sourceMapper = sourceMapper;
        this.segmentMapper = segmentMapper;
        this.linkMapper = linkMapper;
        this.searchService = searchService;
        this.auditService = auditService;
        this.minSuggestScore = minSuggestScore;
    }

    /**
     * Generates SUGGESTED links from every script paragraph to its best semantic
     * matches among VIDEO segments of the same space. Returns the created count;
     * already-suggested or rejected pairs are not duplicated.
     */
    public int suggest(Long userId, Long scriptSourceId) {
        KnowledgeSource script = sourceService.requireOwnedSource(userId, scriptSourceId);
        if (!KnowledgeSourceService.SOURCE_TYPE_SCRIPT.equals(script.getSourceType())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "仅脚本类内容源支持关联建议");
        }
        List<KnowledgeSegment> paragraphs = segmentMapper.selectList(
                new QueryWrapper<KnowledgeSegment>().eq("source_id", scriptSourceId));
        Set<String> knownPairs = existingPairs(scriptSourceId);
        List<KnowledgeLink> created = new ArrayList<>();
        int scanned = 0;
        for (KnowledgeSegment paragraph : paragraphs) {
            if (scanned++ >= MAX_PARAGRAPHS_PER_RUN) break;
            var hits = searchService.search(userId, new com.example.server.dto.KnowledgeSearchRequest(
                    script.getSpaceId(), null, paragraph.getTranscript(), MATCHES_PER_PARAGRAPH * 3, "vector"));
            int kept = 0;
            for (com.example.server.dto.KnowledgeSearchHit hit : hits) {
                if (kept >= MATCHES_PER_PARAGRAPH) break;
                if (!"VIDEO".equals(hit.sourceType()) || hit.sourceId().equals(scriptSourceId) || hit.score() < minSuggestScore) continue;
                if (!knownPairs.add(pairKey(paragraph.getId(), hit.segmentId()))) continue;
                created.add(link(script, paragraph, hit));
                kept++;
            }
        }
        created.forEach(linkMapper::insert);
        auditService.record(userId, "LINK_SUGGESTED", "SOURCE", script.getId(),
                script.getSpaceId(), script.getCollectionId(), "created=" + created.size());
        return created.size();
    }

    /** Outgoing links of one source with both sides' evidence, newest first. */
    public List<KnowledgeLinkView> list(Long userId, Long sourceId, String status) {
        sourceService.requireOwnedSource(userId, sourceId);
        QueryWrapper<KnowledgeLink> query = new QueryWrapper<KnowledgeLink>()
                .eq("source_id", sourceId)
                .orderByDesc("updated_at");
        if (status != null && !status.isBlank()) query.eq("status", status.toUpperCase());
        List<KnowledgeLink> links = linkMapper.selectList(query);
        if (links.isEmpty()) return List.of();

        Set<String> segmentIds = new LinkedHashSet<>();
        Set<Long> sourceIds = new LinkedHashSet<>();
        links.forEach(link -> {
            if (link.getSourceSegmentId() != null) segmentIds.add(link.getSourceSegmentId());
            if (link.getTargetSegmentId() != null) segmentIds.add(link.getTargetSegmentId());
            sourceIds.add(link.getTargetSourceId());
        });
        Map<String, KnowledgeSegment> segments = new LinkedHashMap<>();
        segmentMapper.selectBatchIds(segmentIds)
                .forEach(segment -> segments.put(segment.getId(), segment));
        Map<Long, KnowledgeSource> sources = new LinkedHashMap<>();
        sourceMapper.selectBatchIds(sourceIds)
                .forEach(source -> sources.put(source.getId(), source));

        List<KnowledgeLinkView> views = new ArrayList<>(links.size());
        for (KnowledgeLink link : links) {
            KnowledgeSegment from = segments.get(link.getSourceSegmentId());
            KnowledgeSegment to = segments.get(link.getTargetSegmentId());
            KnowledgeSource target = sources.get(link.getTargetSourceId());
            if (from == null || to == null || target == null || !userId.equals(target.getOwnerUserId())
                    || !"READY".equals(target.getStatus())) continue; // stale half-deleted pair
            views.add(KnowledgeLinkView.from(link, from, to, target));
        }
        return views;
    }

    public KnowledgeLink confirm(Long userId, Long linkId) {
        KnowledgeLink link = requireOwnedLink(userId, linkId);
        link.setStatus(STATUS_CONFIRMED);
        linkMapper.updateById(link);
        auditService.record(userId, "LINK_CONFIRMED", "SOURCE", link.getSourceId(), null, null,
                "linkId=" + linkId);
        return link;
    }

    public KnowledgeLink reject(Long userId, Long linkId) {
        KnowledgeLink link = requireOwnedLink(userId, linkId);
        link.setStatus(STATUS_REJECTED);
        linkMapper.updateById(link);
        auditService.record(userId, "LINK_REJECTED", "SOURCE", link.getSourceId(), null, null,
                "linkId=" + linkId);
        return link;
    }

    private KnowledgeLink requireOwnedLink(Long userId, Long linkId) {
        KnowledgeLink link = linkMapper.selectById(linkId);
        if (link == null) throw new BusinessException(ErrorCode.NOT_FOUND, "关联不存在");
        sourceService.requireOwnedSource(userId, link.getSourceId());
        return link;
    }

    /** suggestion + rejected pairs already recorded for this source. */
    private Set<String> existingPairs(Long scriptSourceId) {
        Set<String> pairs = new LinkedHashSet<>();
        linkMapper.selectList(new QueryWrapper<KnowledgeLink>()
                        .eq("source_id", scriptSourceId)
                        .in("status", STATUS_SUGGESTED, STATUS_CONFIRMED, STATUS_REJECTED))
                .forEach(link -> pairs.add(pairKey(link.getSourceSegmentId(), link.getTargetSegmentId())));
        return pairs;
    }

    private KnowledgeLink link(KnowledgeSource script, KnowledgeSegment paragraph,
                               com.example.server.dto.KnowledgeSearchHit hit) {
        KnowledgeLink link = new KnowledgeLink();
        link.setSourceId(script.getId());
        link.setTargetSourceId(hit.sourceId());
        link.setSourceSegmentId(paragraph.getId());
        link.setTargetSegmentId(hit.segmentId());
        link.setLinkType(LINK_TYPE_SEGMENT_MATCH);
        link.setStatus(STATUS_SUGGESTED);
        link.setConfidence(BigDecimal.valueOf(hit.score()).setScale(4, RoundingMode.HALF_UP));
        return link;
    }

    private static String pairKey(String sourceSegmentId, String targetSegmentId) {
        return sourceSegmentId + ">" + targetSegmentId;
    }
}
