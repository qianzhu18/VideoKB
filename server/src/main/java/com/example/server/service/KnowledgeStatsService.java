package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.FailedAnalysisTask;
import com.example.server.entity.KnowledgeLink;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.entity.User;
import com.example.server.mapper.FailedAnalysisTaskMapper;
import com.example.server.mapper.KnowledgeLinkMapper;
import com.example.server.mapper.KnowledgeSegmentMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import com.example.server.mapper.KnowledgeSpaceMapper;
import com.example.server.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational snapshot behind GET /admin/stats: what is indexed, what is stuck,
 * and what has failed — the numbers an operator needs before deciding to requeue.
 */
@Service
public class KnowledgeStatsService {

    private final UserMapper userMapper;
    private final KnowledgeSpaceMapper spaceMapper;
    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final KnowledgeLinkMapper linkMapper;
    private final FailedAnalysisTaskMapper failedTaskMapper;

    public KnowledgeStatsService(UserMapper userMapper,
                                 KnowledgeSpaceMapper spaceMapper,
                                 KnowledgeSourceMapper sourceMapper,
                                 KnowledgeSegmentMapper segmentMapper,
                                 KnowledgeLinkMapper linkMapper,
                                 FailedAnalysisTaskMapper failedTaskMapper) {
        this.userMapper = userMapper;
        this.spaceMapper = spaceMapper;
        this.sourceMapper = sourceMapper;
        this.segmentMapper = segmentMapper;
        this.linkMapper = linkMapper;
        this.failedTaskMapper = failedTaskMapper;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("users", userMapper.selectCount(null));
        stats.put("spaces", spaceMapper.selectCount(null));
        stats.put("segments", segmentMapper.selectCount(null));

        Map<String, Long> sourcesByStatus = new LinkedHashMap<>();
        for (String status : new String[]{KnowledgeSourceService.STATUS_PENDING,
                KnowledgeSourceService.STATUS_READY, KnowledgeSourceService.STATUS_FAILED,
                KnowledgeSourceService.STATUS_DELETED}) {
            sourcesByStatus.put(status, sourceMapper.selectCount(
                    new QueryWrapper<KnowledgeSource>().eq("status", status)));
        }
        stats.put("sourcesByStatus", sourcesByStatus);

        Map<String, Long> sourcesByType = new LinkedHashMap<>();
        for (String type : new String[]{KnowledgeSourceService.SOURCE_TYPE_VIDEO,
                KnowledgeSourceService.SOURCE_TYPE_SCRIPT}) {
            sourcesByType.put(type, sourceMapper.selectCount(
                    new QueryWrapper<KnowledgeSource>().eq("source_type", type)));
        }
        stats.put("sourcesByType", sourcesByType);

        Map<String, Long> linksByStatus = new LinkedHashMap<>();
        for (String status : new String[]{KnowledgeLinkService.STATUS_SUGGESTED,
                KnowledgeLinkService.STATUS_CONFIRMED, KnowledgeLinkService.STATUS_REJECTED}) {
            linksByStatus.put(status, linkMapper.selectCount(
                    new QueryWrapper<KnowledgeLink>().eq("status", status)));
        }
        stats.put("linksByStatus", linksByStatus);

        stats.put("failedAnalysisTasks", failedTaskMapper.selectCount(
                new QueryWrapper<FailedAnalysisTask>().eq("status", "FAILED")));
        return stats;
    }
}
