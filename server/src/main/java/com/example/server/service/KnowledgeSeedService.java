package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.TranscriptImportRequest;
import com.example.server.dto.VideoContext;
import com.example.server.entity.KnowledgeSegment;
import com.example.server.entity.KnowledgeSource;
import com.example.server.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Zero-reburn migration channel: imports externally produced transcripts (archived
 * analysis checkpoints, subtitle exports) as a VideoContext checkpoint and reuses
 * {@link KnowledgeSegmentIndexService#indexMedia} wholesale, so imported assets get
 * the same chunking, versioned segment rows, vectors, status machine and audit trail
 * as natively analyzed videos. No parallel write path exists here by design.
 */
@Service
public class KnowledgeSeedService {

    static final String IMPORT_SOURCE = "transcript-import";

    private final KnowledgeSourceService sourceService;
    private final AgentCheckpointService checkpointService;
    private final KnowledgeSegmentIndexService indexService;
    private final KnowledgeAuditService auditService;

    public KnowledgeSeedService(KnowledgeSourceService sourceService,
                                AgentCheckpointService checkpointService,
                                KnowledgeSegmentIndexService indexService,
                                KnowledgeAuditService auditService) {
        this.sourceService = sourceService;
        this.checkpointService = checkpointService;
        this.indexService = indexService;
        this.auditService = auditService;
    }

    public ImportResult importTranscript(Long userId, TranscriptImportRequest request) {
        KnowledgeSource source = sourceService.requireSourceByMediaId(request.mediaId());
        if (!userId.equals(source.getOwnerUserId())) {
            throw new SecurityException("无权访问该内容源");
        }
        List<VideoContext.VideoSegment> videoSegments = toVideoSegments(request.segments());
        if (videoSegments.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "转写分段内容为空：每段都缺少文本与 OCR");
        }
        checkpointService.saveContext(source.getMediaId(), new VideoContext(
                IMPORT_SOURCE,
                request.goal() == null ? "" : request.goal().trim(),
                videoSegments));
        List<KnowledgeSegment> indexed = indexService.indexMedia(source.getMediaId());
        auditService.record(userId, "SOURCE_SEEDED", "SOURCE", source.getId(),
                source.getSpaceId(), source.getCollectionId(),
                "segments=" + indexed.size() + ";source=" + IMPORT_SOURCE);
        return new ImportResult(source.getMediaId(), source.getId(), indexed.size());
    }

    /** Drops fully-empty windows up front so the indexer never sees a contentless context. */
    private List<VideoContext.VideoSegment> toVideoSegments(List<TranscriptImportRequest.TranscriptSegment> segments) {
        List<VideoContext.VideoSegment> videoSegments = new ArrayList<>(segments.size());
        for (TranscriptImportRequest.TranscriptSegment segment : segments) {
            if (segment == null || segment.startMs() == null || segment.endMs() == null) continue;
            String text = segment.text() == null ? "" : segment.text().trim();
            List<String> ocr = segment.ocr() == null ? List.of()
                    : segment.ocr().stream().filter(value -> value != null && !value.isBlank()).toList();
            if (text.isEmpty() && ocr.isEmpty()) continue;
            videoSegments.add(new VideoContext.VideoSegment(
                    segment.startMs(), segment.endMs(), text, ocr, List.of()));
        }
        return videoSegments;
    }

    public record ImportResult(Long mediaId, Long sourceId, int importedSegments) {
    }
}
