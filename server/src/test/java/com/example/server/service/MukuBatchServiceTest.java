package com.example.server.service;

import com.example.server.dto.MukuBatchRequest;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.KnowledgeSourceLocationRequest;
import com.example.server.entity.MediaFile;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MukuBatchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void submitPersistsUserScopedBatchAndSchedulesWork() throws Exception {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> null).when(executor).execute(any(Runnable.class));
        YtDlpUtils validator = mock(YtDlpUtils.class);
        MukuBatchService service = service("/bin/echo", executor, validator);
        MukuBatchRequest request = new MukuBatchRequest(
                List.of("https://www.bilibili.com/video/BV1"), null, null, "提取 Java 并发要点", 2, null);

        Map<String, Object> accepted = service.submit(42L, request);
        String batchId = String.valueOf(accepted.get("batchId"));

        assertEquals("QUEUED", accepted.get("state"));
        assertTrue(batchId.matches("[a-f0-9]{32}"));
        assertEquals(batchId, service.get(42L, batchId).get("batchId"));
        assertThrows(IllegalArgumentException.class, () -> service.get(99L, batchId));
    }

    @Test
    void mukuAvailabilityIsExplicitAndUnavailableBatchesAreRejected() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        MukuBatchService service = service("/missing/muku", executor, mock(YtDlpUtils.class));

        assertFalse((Boolean) service.status().get("available"));
        assertThrows(IllegalStateException.class, () -> service.submit(42L,
                new MukuBatchRequest(List.of("https://example.com/video"), null, null, "", 1, null)));
    }

    @Test
    void rejectsPrivateUrlsBeforeQueueing() throws Exception {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        YtDlpUtils validator = mock(YtDlpUtils.class);
        doThrow(new IllegalArgumentException("不允许访问内网地址"))
                .when(validator).validatePublicHttpUrl("http://127.0.0.1/video");
        MukuBatchService service = service("/bin/echo", executor, validator);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.submit(42L,
                new MukuBatchRequest(List.of("http://127.0.0.1/video"), null, null, "", 1, null)));

        assertTrue(error.getMessage().contains("链接校验失败"));
    }

    @Test
    void downloadsRegistersAndQueuesAnalysisWithTheCustomGoal() throws Exception {
        Path muku = tempDir.resolve("muku-test");
        Files.writeString(muku, """
                #!/usr/bin/env python3
                import json, pathlib, sys
                args = sys.argv[1:]
                def value(flag): return pathlib.Path(args[args.index(flag) + 1])
                source = value('--input-file').read_text().splitlines()[0]
                output = value('--output-dir')
                output.mkdir(parents=True, exist_ok=True)
                video = output / 'lesson.mp4'
                video.write_bytes(b'video')
                row = {'source_url': source, 'title': 'Lesson', 'download_path': str(video)}
                value('--result-file').write_text(json.dumps({'results': [row]}))
                print(json.dumps({'event': 'task_done', **row}), flush=True)
                """, StandardCharsets.UTF_8);
        assertTrue(muku.toFile().setExecutable(true));

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        YtDlpUtils validator = mock(YtDlpUtils.class);
        MediaIngestService ingest = mock(MediaIngestService.class);
        AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        MediaFile media = new MediaFile();
        media.setId(31L);
        media.setUserId(42L);
        when(ingest.ingestDownloadedFile(any(Path.class), anyString(), eq(42L))).thenReturn(media);
        when(dispatch.submitBulk(eq(media), eq("提取 Java 并发要点"), eq(AnalysisMode.GENERAL)))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);
        MukuBatchService service = new MukuBatchService(muku.toString(), tempDir.toString(),
                new ObjectMapper(), executor, validator, ingest, dispatch,
                sourceService, spaceService);

        Map<String, Object> accepted = service.submit(42L, new MukuBatchRequest(
                List.of("https://www.bilibili.com/video/BV1"), 77L, null, "提取 Java 并发要点", 1, null));
        @SuppressWarnings("unchecked") Map<String, Object> item =
                (Map<String, Object>) ((List<?>) accepted.get("items")).get(0);

        assertEquals("COMPLETED", accepted.get("state"));
        assertEquals("SUBMITTED", item.get("state"));
        assertEquals(31L, item.get("mediaId"));
        verify(sourceService).attachExistingMediaWithoutAutoDispatch(
                42L, 31L, new KnowledgeSourceLocationRequest(77L, null));
        verify(dispatch).submitBulk(media, "提取 Java 并发要点", AnalysisMode.GENERAL);
    }

    private MukuBatchService service(String executable,
                                     ThreadPoolTaskExecutor executor,
                                     YtDlpUtils validator) {
        return new MukuBatchService(executable, tempDir.toString(), new ObjectMapper(), executor,
                validator, mock(MediaIngestService.class), mock(AnalysisDispatchService.class),
                mock(KnowledgeSourceService.class), mock(KnowledgeSpaceService.class));
    }
}
