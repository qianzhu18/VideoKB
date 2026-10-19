package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.springframework.stereotype.Service;

/** Transcription and indexing complete without invoking AgentLoopService. */
@Service
public class KnowledgeIngestPipeline {
    private final VideoPreparationService preparation;
    private final KnowledgeSegmentIndexService index;
    private final MediaFileMapper media;
    private final AgentTelemetry telemetry;
    private final KnowledgeMetrics metrics;

    public KnowledgeIngestPipeline(VideoPreparationService preparation, KnowledgeSegmentIndexService index,
                                   MediaFileMapper media, AgentTelemetry telemetry, KnowledgeMetrics metrics) {
        this.preparation = preparation; this.index = index; this.media = media; this.telemetry = telemetry; this.metrics = metrics;
    }

    public void ingest(Long mediaId, Runnable indexingStage) {
        MediaFile file = media.selectById(mediaId);
        if (file == null) throw new IllegalArgumentException("视频已删除");
        String traceId = telemetry.start(mediaId, "知识入库", AnalysisMode.GENERAL);
        telemetry.bind(traceId);
        try {
            var context = metrics.measure("ingest.extraction", () -> preparation.prepare(file, "知识入库", traceId, AnalysisMode.GENERAL));
            // Update only transcript: a simultaneous report must not overwrite/lose its summary.
            MediaFile patch = new MediaFile(); patch.setId(mediaId); patch.setTranscriptText(context.transcriptText());
            media.updateById(patch);
            indexingStage.run();
            metrics.measure("ingest.index", () -> index.indexMedia(mediaId));
        } finally { telemetry.flush(traceId); telemetry.clear(); }
    }
}
