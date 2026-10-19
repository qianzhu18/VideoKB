package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.KnowledgeSourceLocationRequest;
import com.example.server.dto.MukuBatchRequest;
import com.example.server.entity.MediaFile;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Muku downloads URL batches; the existing Java analysis and RAG pipeline owns parsing/indexing. */
@Service
public class MukuBatchService {

    private static final Logger log = LoggerFactory.getLogger(MukuBatchService.class);
    private static final String DEFAULT_GOAL = "理解视频核心内容并生成结构化分析报告";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String executable;
    private final Path workRoot;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;
    private final YtDlpUtils urlValidator;
    private final MediaIngestService mediaIngestService;
    private final AnalysisDispatchService dispatchService;
    private final KnowledgeSourceService sourceService;
    private final KnowledgeSpaceService spaceService;
    private final ConcurrentHashMap<String, Map<String, Object>> activeBatches = new ConcurrentHashMap<>();

    public MukuBatchService(@Value("${tool.muku.path:muku}") String executable,
                            @Value("${tool.muku.work-dir:${java.io.tmpdir}/videokb-muku}") String workDir,
                            ObjectMapper objectMapper,
                            @Qualifier("mukuBatchExecutor") ThreadPoolTaskExecutor executor,
                            YtDlpUtils urlValidator,
                            MediaIngestService mediaIngestService,
                            AnalysisDispatchService dispatchService,
                            KnowledgeSourceService sourceService,
                            KnowledgeSpaceService spaceService) {
        this.executable = executable;
        this.workRoot = Path.of(workDir).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.urlValidator = urlValidator;
        this.mediaIngestService = mediaIngestService;
        this.dispatchService = dispatchService;
        this.sourceService = sourceService;
        this.spaceService = spaceService;
    }

    public Map<String, Object> status() {
        Path binary = findExecutable(executable);
        return Map.of("available", binary != null, "executable", binary == null ? "" : binary.toString());
    }

    public Map<String, Object> submit(Long userId, MukuBatchRequest request) {
        if (findExecutable(executable) == null) {
            throw new IllegalStateException("运行 Java 服务的环境没有安装 Muku CLI");
        }
        List<String> links = normalizeLinks(request.links());
        Long spaceId = request.spaceId();
        if (request.collectionId() != null && spaceId == null) {
            throw new IllegalArgumentException("选择目录时必须同时选择知识空间");
        }
        if (spaceId != null) spaceService.requireOwnedSpace(userId, spaceId);
        String batchId = request.batchId() == null || request.batchId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : validateBatchId(request.batchId());
        String key = userId + ":" + batchId;
        Map<String, Object> current = activeBatches.get(key);
        if (current != null && "PROCESSING".equals(current.get("state"))) return snapshot(current);

        Path root = batchRoot(userId, batchId);
        Map<String, Object> batch = current == null ? readBatch(root) : current;
        if (batch != null && !links.equals(batch.get("links"))) {
            throw new IllegalArgumentException("续跑批次的链接必须与原批次一致");
        }
        if (batch != null && "COMPLETED".equals(batch.get("state"))) return snapshot(batch);
        if (batch == null) batch = newBatch(batchId, links, request);
        batch.put("state", "QUEUED");
        batch.put("stage", "QUEUED");
        batch.put("message", "批次已加入下载队列");
        activeBatches.put(key, batch);
        saveBatch(root, batch);
        Map<String, Object> scheduled = batch;
        try {
            executor.execute(() -> runBatch(userId, scheduled, root));
        } catch (RuntimeException rejected) {
            batch.put("state", "FAILED");
            batch.put("stage", "FAILED");
            batch.put("message", "批量任务队列已满，请稍后重试");
            saveBatch(root, batch);
            throw rejected;
        }
        return snapshot(batch);
    }

    public Map<String, Object> get(Long userId, String batchId) {
        String normalizedId = validateBatchId(batchId);
        String key = userId + ":" + normalizedId;
        Map<String, Object> batch = activeBatches.get(key);
        if (batch == null) {
            batch = readBatch(batchRoot(userId, normalizedId));
            if (batch != null) activeBatches.putIfAbsent(key, batch);
        }
        if (batch == null) throw new IllegalArgumentException("批量任务不存在");
        return snapshot(batch);
    }

    private Map<String, Object> newBatch(String batchId, List<String> links, MukuBatchRequest request) {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", batchId);
        batch.put("state", "QUEUED");
        batch.put("stage", "QUEUED");
        batch.put("links", links);
        batch.put("spaceId", request.spaceId());
        batch.put("collectionId", request.collectionId());
        batch.put("analysisGoal", cleanGoal(request.analysisGoal()));
        batch.put("jobs", request.jobs() == null ? 2 : Math.max(1, Math.min(8, request.jobs())));
        batch.put("completedDownloads", 0);
        batch.put("completedImports", 0);
        batch.put("message", "批次已加入下载队列");
        List<Map<String, Object>> items = new ArrayList<>();
        for (String link : links) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceUrl", link);
            item.put("title", link);
            item.put("state", "QUEUED");
            item.put("downloadState", "QUEUED");
            items.add(item);
        }
        batch.put("items", items);
        return batch;
    }

    private void runBatch(Long userId, Map<String, Object> batch, Path root) {
        synchronized (batch) {
            batch.put("state", "PROCESSING");
            batch.put("stage", "DOWNLOADING");
            batch.put("message", "Muku 正在下载视频");
            saveBatch(root, batch);
        }
        try {
            Path mediaDir = root.resolve("media").normalize();
            Files.createDirectories(mediaDir);
            Path inputFile = root.resolve("urls.txt");
            Path resultFile = root.resolve("results.json");
            @SuppressWarnings("unchecked") List<String> links = (List<String>) batch.get("links");
            Files.writeString(inputFile, String.join("\n", links) + "\n", StandardCharsets.UTF_8);
            runMuku(batch, root, inputFile, mediaDir, resultFile);
            mergeResultFile(batch, resultFile);

            synchronized (batch) {
                batch.put("stage", "IMPORTING");
                batch.put("message", "下载完成，正在注册视频并提交解析任务");
                saveBatch(root, batch);
            }
            for (Map<String, Object> item : items(batch)) importOne(userId, batch, item, root);

            long imported = items(batch).stream().filter(item -> "SUBMITTED".equals(item.get("state"))).count();
            long failed = items(batch).size() - imported;
            batch.put("state", failed == 0 ? "COMPLETED" : imported > 0 ? "PARTIAL" : "FAILED");
            batch.put("stage", "COMPLETED");
            batch.put("completedImports", imported);
            batch.put("message", "已导入并提交解析 " + imported + " 个，失败 " + failed + " 个");
        } catch (Exception exception) {
            log.warn("muku_batch_failed batchId={}", batch.get("batchId"), exception);
            batch.put("state", "FAILED");
            batch.put("stage", "FAILED");
            batch.put("message", safeMessage(exception));
        } finally {
            saveBatch(root, batch);
            activeBatches.remove(userId + ":" + batch.get("batchId"), batch);
        }
    }

    private void runMuku(Map<String, Object> batch, Path root, Path inputFile,
                         Path mediaDir, Path resultFile) throws Exception {
        Path binary = findExecutable(executable);
        if (binary == null) throw new IllegalStateException("运行 VideoKB 的环境没有安装 Muku CLI");
        List<String> command = List.of(binary.toString(), "download", "--input-file", inputFile.toString(),
                "--preset", "Highest Video (MP4)", "--output-dir", mediaDir.toString(),
                "--result-file", resultFile.toString(), "--jobs", String.valueOf(batch.get("jobs")),
                "--resume", "--stream", "--no-transcript");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        Thread reader = Thread.ofVirtual().start(() -> readMukuEvents(process, batch, root));
        if (!process.waitFor(2, TimeUnit.HOURS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Muku 批量下载超过两小时，已停止；可用原批次 ID 继续");
        }
        reader.join(TimeUnit.SECONDS.toMillis(15));
        if (reader.isAlive()) reader.interrupt();
        if (process.exitValue() != 0 && !Files.isRegularFile(resultFile)) {
            throw new IllegalStateException("Muku 下载失败，退出码 " + process.exitValue());
        }
    }

    private void readMukuEvents(Process process, Map<String, Object> batch, Path root) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode event;
                try {
                    event = objectMapper.readTree(line);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.path("event").asText();
                if (!"task_done".equals(type) && !"task_failed".equals(type)) continue;
                String url = event.path("source_url").asText();
                Map<String, Object> item = findItem(batch, url);
                if (item == null) continue;
                item.put("title", event.path("title").asText(url));
                item.put("downloadState", "task_failed".equals(type) ? "FAILED" : "DOWNLOADED");
                item.put("downloadPath", event.path("download_path").asText(""));
                if (event.hasNonNull("error")) item.put("error", event.path("error").asText());
                updateDownloadCount(batch);
                saveBatch(root, batch);
            }
        } catch (IOException exception) {
            log.warn("muku_progress_stream_failed batchId={}", batch.get("batchId"), exception);
        }
    }

    private void mergeResultFile(Map<String, Object> batch, Path resultFile) throws IOException {
        if (!Files.isRegularFile(resultFile)) return;
        JsonNode payload = objectMapper.readTree(resultFile.toFile());
        JsonNode results = payload.isArray() ? payload : payload.path("results");
        if (!results.isArray()) return;
        for (JsonNode result : results) {
            String url = result.path("source_url").asText(result.path("url").asText());
            Map<String, Object> item = findItem(batch, url);
            if (item == null) continue;
            item.put("title", result.path("title").asText(url));
            String path = result.path("download_path").asText(result.path("path").asText(""));
            item.put("downloadPath", path);
            if (result.hasNonNull("error")) {
                item.put("error", result.path("error").asText());
                item.put("downloadState", "FAILED");
            } else if (!path.isBlank()) {
                item.put("downloadState", "DOWNLOADED");
            }
        }
        updateDownloadCount(batch);
    }

    private void importOne(Long userId, Map<String, Object> batch,
                           Map<String, Object> item, Path root) {
        if ("SUBMITTED".equals(item.get("state"))) return;
        String downloadPath = String.valueOf(item.getOrDefault("downloadPath", ""));
        if (downloadPath.isBlank() || "FAILED".equals(item.get("downloadState"))) {
            item.put("state", "FAILED");
            item.put("error", item.getOrDefault("error", "Muku 没有下载出可导入的视频文件"));
            saveBatch(root, batch);
            return;
        }
        try {
            Path mediaRoot = root.resolve("media").toRealPath();
            Path video = Path.of(downloadPath).toRealPath();
            if (!video.startsWith(mediaRoot) || !Files.isRegularFile(video)) {
                throw new IllegalArgumentException("Muku 输出不在本批次目录内，已跳过");
            }
            if (Files.size(video) > 2L * 1024 * 1024 * 1024) {
                throw new IllegalArgumentException("单个视频不能超过 2 GB");
            }
            item.put("state", "IMPORTING");
            saveBatch(root, batch);
            String title = String.valueOf(item.getOrDefault("title", video.getFileName().toString()));
            if (!title.matches("(?i).*\\.(mp4|mov|mkv|avi|webm|m4v)$")) {
                String suffix = video.getFileName().toString().replaceFirst("^.*(\\.[^.]+)$", "$1");
                title = title + (suffix.startsWith(".") ? suffix : ".mp4");
            }
            MediaFile media = mediaIngestService.ingestDownloadedFile(video, title, userId);
            Long spaceId = asLong(batch.get("spaceId"));
            Long collectionId = asLong(batch.get("collectionId"));
            if (spaceId != null) {
                sourceService.attachExistingMediaWithoutAutoDispatch(userId, media.getId(),
                        new KnowledgeSourceLocationRequest(spaceId, collectionId));
            }
            String goal = cleanGoal(String.valueOf(batch.getOrDefault("analysisGoal", "")));
            if (!goal.isBlank()) {
                var submission = dispatchService.submitBulk(media, goal, AnalysisMode.GENERAL);
                if (submission != AnalysisDispatchService.SubmissionResult.ACCEPTED
                        && submission != AnalysisDispatchService.SubmissionResult.DUPLICATE) {
                    // Knowledge has its own durable job; optional report failure does not fail import.
                    item.put("reportWarning", "报告未提交：" + submission);
                }
            }
            item.put("mediaId", media.getId());
            item.put("state", "SUBMITTED");
            item.remove("error");
            batch.put("completedImports", items(batch).stream()
                    .filter(row -> "SUBMITTED".equals(row.get("state"))).count());
            saveBatch(root, batch);
            // MinIO now owns the durable copy. Keep failed items for Muku resume; remove successful payloads.
            Files.deleteIfExists(video);
        } catch (Exception exception) {
            item.put("state", "FAILED");
            item.put("error", safeMessage(exception));
            saveBatch(root, batch);
        }
    }

    private List<String> normalizeLinks(List<String> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100) {
            throw new IllegalArgumentException("单批请提供 1 到 100 条链接");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        try {
            for (String value : requested) {
                String link = value == null ? "" : value.trim();
                if (link.isBlank()) continue;
                urlValidator.validatePublicHttpUrl(link);
                normalized.add(link);
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("链接校验失败：" + safeMessage(exception), exception);
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("请至少填写一个完整视频链接");
        return List.copyOf(normalized);
    }

    private String validateBatchId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[A-Za-z0-9-]{8,64}")) throw new IllegalArgumentException("批次 ID 无效");
        return id;
    }

    private Path batchRoot(Long userId, String batchId) {
        Path root = workRoot.resolve(String.valueOf(userId)).resolve(batchId).normalize();
        if (!root.startsWith(workRoot)) throw new IllegalArgumentException("批次目录无效");
        return root;
    }

    private Path findExecutable(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        Path direct = Path.of(candidate);
        if (direct.isAbsolute() || candidate.contains("/")) {
            return Files.isRegularFile(direct) && Files.isExecutable(direct) ? direct.toAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path found = Path.of(dir).resolve(candidate);
            if (Files.isRegularFile(found) && Files.isExecutable(found)) return found.toAbsolutePath();
        }
        return null;
    }

    private Map<String, Object> readBatch(Path root) {
        Path file = root.resolve("batch.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return objectMapper.readValue(file.toFile(), MAP_TYPE);
        } catch (IOException exception) {
            log.warn("muku_batch_state_read_failed path={}", file, exception);
            return null;
        }
    }

    private void saveBatch(Path root, Map<String, Object> batch) {
        synchronized (batch) {
            try {
                Files.createDirectories(root);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("batch.json").toFile(), batch);
            } catch (IOException exception) {
                log.error("muku_batch_state_write_failed batchId={}", batch.get("batchId"), exception);
            }
        }
    }

    private Map<String, Object> snapshot(Map<String, Object> batch) {
        synchronized (batch) {
            return objectMapper.convertValue(batch, MAP_TYPE);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> batch) {
        return (List<Map<String, Object>>) batch.getOrDefault("items", List.of());
    }

    private Map<String, Object> findItem(Map<String, Object> batch, String url) {
        if (url == null || url.isBlank()) return null;
        return items(batch).stream().filter(item -> url.equals(item.get("sourceUrl"))).findFirst().orElse(null);
    }

    private void updateDownloadCount(Map<String, Object> batch) {
        batch.put("completedDownloads", items(batch).stream()
                .filter(item -> List.of("DOWNLOADED", "FAILED").contains(item.get("downloadState"))).count());
    }

    private String cleanGoal(String goal) {
        String value = goal == null ? "" : goal.trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
