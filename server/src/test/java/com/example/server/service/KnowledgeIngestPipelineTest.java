package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIngestPipelineTest {
    @Test void contextAndIndexCompleteIndependentlyOfReports() {
        var prep = mock(VideoPreparationService.class); var index = mock(KnowledgeSegmentIndexService.class);
        var media = mock(MediaFileMapper.class); var traces = mock(AgentTelemetry.class);
        var file = new MediaFile(); file.setId(5L); file.setAiSummary("report already saved");
        when(media.selectById(5L)).thenReturn(file); when(traces.start(any(), any(), any())).thenReturn("test");
        when(prep.prepare(any(), any(), any(), any())).thenReturn(new VideoContext("test", "", List.of(
                new VideoContext.VideoSegment(0, 1000, "evidence", List.of(), List.of()))));
        var metrics = new SimpleMeterRegistry();
        new KnowledgeIngestPipeline(prep, index, media, traces, new KnowledgeMetrics(metrics)).ingest(5L, () -> {});
        verify(media).updateById(argThat((MediaFile patch) -> patch.getAiSummary() == null && "evidence".equals(patch.getTranscriptText())));
        verify(index).indexMedia(5L);
        assertEquals(1, metrics.get("knowledge.stage").tag("stage", "ingest.index").timer().count());
    }
}
