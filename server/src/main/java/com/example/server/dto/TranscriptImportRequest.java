package com.example.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Zero-reburn transcript import for one registered media asset: timestamped segments
 * produced elsewhere (e.g. an archived analysis checkpoint) enter the SAME indexing
 * pipeline as natively analyzed videos — chunk summaries, MySQL segment rows, Qdrant
 * vectors, status machine and audit trail included.
 */
public record TranscriptImportRequest(
        @NotNull(message = "媒体 ID 不能为空")
        Long mediaId,
        /** Optional provenance goal; recorded on the checkpoint for traceability. */
        String goal,
        @NotEmpty(message = "转写分段不能为空")
        @Valid
        List<TranscriptSegment> segments
) {

    public record TranscriptSegment(
            @NotNull(message = "startMs 不能为空")
            Long startMs,
            @NotNull(message = "endMs 不能为空")
            Long endMs,
            /** ASR transcript text of the window. */
            String text,
            /** OCR texts captured in the window, if any. */
            List<String> ocr
    ) {
    }
}
