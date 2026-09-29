package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.MediaSummary;
import com.example.server.entity.MediaFile;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AuthService;
import com.example.server.service.ChunkUploadService;
import com.example.server.service.MediaIngestService;
import com.example.server.service.MediaService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/media")
public class MediaController {

    /** Uploads enter the analysis pipeline automatically: the user's job ends at the
     *  upload gesture — transcription, indexing and knowledge-base readiness follow
     *  without any further click. Failures degrade to the manual Video Agent path. */
    static final String DEFAULT_ANALYSIS_GOAL = "理解视频核心内容并生成结构化分析报告";

    private final ChunkUploadService chunkUploadService;
    private final MediaIngestService mediaIngestService;
    private final MediaService mediaService;
    private final AnalysisDispatchService dispatchService;

    public MediaController(ChunkUploadService chunkUploadService,
                           MediaIngestService mediaIngestService,
                           MediaService mediaService,
                           AnalysisDispatchService dispatchService) {
        this.chunkUploadService = chunkUploadService;
        this.mediaIngestService = mediaIngestService;
        this.mediaService = mediaService;
        this.dispatchService = dispatchService;
    }

    // 说明：下列方法上的 throws 源于 service 层声明了受检异常（throws Exception/IOException）。
    // 受检异常必须在编译期被处理，而 @RestControllerAdvice 只在运行期兜底，故此处显式向上抛出，
    // 由全局异常处理器统一转成 Result。更彻底的做法是收敛 service 的受检异常签名（留待 service 批次）。
    @PostMapping("/init-upload")
    public Result<String> initUpload(@RequestParam String filename,
                                     @RequestParam int totalChunks,
                                     @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(chunkUploadService.initialize(filename, totalChunks, userId));
    }

    @GetMapping("/upload-status")
    public Result<Set<Integer>> uploadStatus(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(chunkUploadService.uploadedChunks(uploadId, userId));
    }

    /** Looks up an exact content match for this user before the client opens an upload session. */
    @GetMapping("/duplicate")
    public Result<MediaSummary> findDuplicate(
            @RequestParam String contentHash,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        if (contentHash == null || !contentHash.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalArgumentException("contentHash must be a 32-character MD5 hex string");
        }
        MediaFile existing = mediaService.findDuplicateByContent(
                userId, contentHash.toLowerCase(java.util.Locale.ROOT));
        return Result.ok(existing == null ? null : MediaSummary.from(existing));
    }

    @PostMapping("/upload-chunk")
    public Result<Void> uploadChunk(@RequestParam String uploadId,
                                    @RequestParam int chunkIndex,
                                    @RequestParam int totalChunks,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        chunkUploadService.uploadChunk(uploadId, chunkIndex, totalChunks, file, userId);
        return Result.ok();
    }

    @PostMapping("/complete-upload")
    public Result<MediaSummary> completeUpload(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        MediaSummary media = MediaSummary.from(chunkUploadService.complete(uploadId, userId));
        dispatchDefaultAnalysis(media.id(), userId);
        return Result.ok(media);
    }

    @PostMapping("/upload")
    public Result<MediaSummary> upload(@RequestParam("file") MultipartFile file,
                                       @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        MediaSummary media = MediaSummary.from(mediaIngestService.ingestFile(file, userId));
        dispatchDefaultAnalysis(media.id(), userId);
        return Result.ok(media);
    }

    @PostMapping("/upload-url")
    public Result<MediaSummary> uploadUrl(@RequestParam("url") String url,
                                          @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        MediaSummary media = MediaSummary.from(mediaIngestService.ingestUrl(url, userId));
        dispatchDefaultAnalysis(media.id(), userId);
        return Result.ok(media);
    }

    /** Best-effort: a dispatch failure must not fail the upload response — the media
     *  exists and the card's Video Agent remains the manual start path. */
    private void dispatchDefaultAnalysis(Long mediaId, Long userId) {
        try {
            MediaFile mediaFile = mediaService.requireOwnedMedia(mediaId, userId);
            dispatchService.submitBulk(mediaFile, DEFAULT_ANALYSIS_GOAL, AnalysisMode.GENERAL);
        } catch (RuntimeException e) {
            // Dedup replays, rate limits and quota exhaustion all land here; the upload
            // itself is already durable and the user can start analysis from the card.
        }
    }

    @GetMapping("/list")
    public Result<List<MediaSummary>> getList(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.listByUser(userId).stream()
                .map(MediaSummary::from)
                .toList());
    }

    @GetMapping("/playback")
    public Result<String> playback(@RequestParam Long id,
                                   @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        return Result.ok(mediaService.readableSource(mediaFile.getFilePath()));
    }

    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam("id") Long id,
                               @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.deleteOwnedMedia(id, userId);
        return Result.ok();
    }
}
