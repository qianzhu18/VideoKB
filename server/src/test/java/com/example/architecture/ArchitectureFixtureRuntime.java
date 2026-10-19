package com.example.architecture;

import com.example.server.ServerApplication;
import com.example.server.dto.*;
import com.example.server.service.*;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Test-only runtime. Real DB/MQ/Redis/Qdrant/MinIO, deterministic AI, no paid model calls. */
@Configuration
@Import(ServerApplication.class)
public class ArchitectureFixtureRuntime {
    private static final AtomicBoolean EMBEDDING_FAILURE = new AtomicBoolean(false);
    public static void main(String[] args) {
        new SpringApplicationBuilder(ArchitectureFixtureRuntime.class).run(args);
    }
    @Bean @Primary VideoPreparationService fixturePreparation(AgentCheckpointService checkpoints) {
        var service = mock(VideoPreparationService.class);
        var context = new VideoContext("fixture", "", List.of(
                new VideoContext.VideoSegment(0, 60000, "缓存击穿需要互斥锁和逻辑过期。", List.of(), List.of()),
                new VideoContext.VideoSegment(60000, 120000, "前端请求缓存可以减少后端数据库压力。", List.of(), List.of())));
        when(service.prepare(any(), any(), any(), any())).thenAnswer(call -> {
            var file = (com.example.server.entity.MediaFile) call.getArgument(0);
            checkpoints.saveContext(file.getId(), context);
            return context;
        });
        return service;
    }
    @Bean @Primary VideoChunkingService fixtureChunking() {
        var service = mock(VideoChunkingService.class);
        when(service.build(anyList())).thenAnswer(call -> List.of(new VideoChunk(0, 120000,
                "缓存和前后端协同", List.of("缓存"), call.getArgument(0), List.of(1.0, 0.0, 0.0))));
        return service;
    }
    @Bean @Primary EmbeddingUtils fixtureEmbedding() {
        var service = mock(EmbeddingUtils.class);
        when(service.embed(anyString())).thenAnswer(call -> {
            if (EMBEDDING_FAILURE.get()) throw new IllegalStateException("fixture embedding failure");
            return List.of(1.0, 0.0, 0.0);
        });
        when(service.embedBatch(anyList())).thenAnswer(call -> {
            if (EMBEDDING_FAILURE.get()) throw new IllegalStateException("fixture embedding failure");
            return ((List<?>) call.getArgument(0)).stream().map(x -> List.of(1.0, 0.0, 0.0)).toList();
        });
        return service;
    }
    @Bean @Primary AgentLoopService fixtureAgent() {
        var service = mock(AgentLoopService.class);
        when(service.run(any(), any(), any())).thenThrow(new AgentLoopService.BudgetExceededException("fixture report budget exceeded"));
        return service;
    }
    @RestController
    static class FixtureControls {
        @PostMapping("/architecture-fixture/embedding-failure")
        public boolean setFailure(@RequestParam boolean enabled) { EMBEDDING_FAILURE.set(enabled); return enabled; }
    }
}
