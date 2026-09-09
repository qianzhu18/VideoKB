"""5 分钟分块(移植自 service/VideoChunkingService.java)。

每块:LLM 摘要 {segmentSummary≤200字, keywords[]} 失败 → 降级为 transcript+OCR 拼接截 500 字、
keywords 空;embedding = summary + keywords,失败 → 空列表(纯关键词路径)。
"""

from __future__ import annotations

from dovideo.config import Settings
from dovideo.core.llm import (
    EmbeddingUnavailableError,
    LLMClient,
    PermanentLLMError,
    RetriableLLMError,
    StructuredParseError,
    structured_chat,
)
from dovideo.core.models import VideoChunk, VideoContext
from dovideo.core.prompts import chunk_summary_messages
from dovideo.core.telemetry import Telemetry
from dovideo.core.textnorm import clip


def _chunk_body(segments_text: list[str], ocr_texts: list[str]) -> str:
    parts = [t for t in segments_text if t.strip()]
    if ocr_texts:
        parts.append("OCR: " + " | ".join(ocr_texts))
    return "\n".join(parts)


async def build_chunks(
    context: VideoContext,
    *,
    settings: Settings,
    llm: LLMClient,
    telemetry: Telemetry,
    budget=None,
) -> list[VideoChunk]:
    if not context.segments:
        return []
    chunk_ms = settings.chunk_ms
    last_start = max(s.start_ms for s in context.segments)
    chunks: list[VideoChunk] = []

    start = 0
    while start <= last_start:
        end = start + chunk_ms
        indexes = [
            i
            for i, seg in enumerate(context.segments)
            if start <= seg.start_ms < end
        ]
        if indexes:
            chunk = await _build_one(
                context, indexes, start, end, settings=settings, llm=llm, telemetry=telemetry, budget=budget
            )
            chunks.append(chunk)
        start = end
    return chunks


async def _build_one(
    context: VideoContext,
    indexes: list[int],
    start: int,
    end: int,
    *,
    settings: Settings,
    llm: LLMClient,
    telemetry: Telemetry,
    budget,
) -> VideoChunk:
    segments = [context.segments[i] for i in indexes]
    body = _chunk_body([s.transcript for s in segments], [t for s in segments for t in s.ocr_texts])
    summary = ""
    keywords: list[str] = []
    try:
        payload = await structured_chat(
            llm,
            chunk_summary_messages(start, end, body),
            timeout_s=settings.llm_timeout_seconds,
            budget=budget or _NullBudget(),
            telemetry=telemetry,
        )
        summary = str(payload.get("segmentSummary", "")).strip()
        kw = payload.get("keywords")
        if isinstance(kw, list):
            keywords = [str(k) for k in kw if str(k).strip()]
        if not summary:
            raise StructuredParseError("摘要为空")
    except (RetriableLLMError, PermanentLLMError, StructuredParseError) as exc:
        # 摘要降级:原文头 500 字,关键词空,不阻断主链路
        telemetry.incr("summaryFallbacks")
        summary = clip(body, 500, suffix="")

    embedding: list[float] = []
    try:
        vectors = await llm.embed([f"{summary}\n{' '.join(keywords)}"])
        embedding = vectors[0] if vectors else []
    except EmbeddingUnavailableError:
        telemetry.incr("embeddingFallbacks")

    return VideoChunk(
        start_ms=start,
        end_ms=end,
        segment_summary=summary,
        keywords=keywords,
        embedding=embedding,
        segment_indexes=indexes,
    )


class _NullBudget:
    """摘要调用不走总预算时的占位(独立小调用)。"""

    def model_timeout_seconds(self, t: float) -> float:
        return t

    def check(self, **_) -> None:  # noqa: ANN003
        return None
