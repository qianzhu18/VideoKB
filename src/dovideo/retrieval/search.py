"""混合检索(移植自 service/VideoEvidenceRetrievalService.java / LongVideoContextService.java)。

- 查询规划 LLM → {semanticQuery, keywords, visualKeywords},失败降级标点切词前 8 个
- chunk 级打分: semantic*0.6 + term(keywords)*0.25 + term(visual→OCR)*0.15
- segment 级打分: chunk*0.55 + transcript*0.25 + visual*0.20
- 上下文打包: 24k 字符预算,第一条永远保留
- 定向重检索: requiredTimestamps 邻近段(margin=max(60s,段长))∪ critiqueQuery 重检索 ∪ 原选中集
"""

from __future__ import annotations

import re

from pydantic import BaseModel

from dovideo.config import Settings
from dovideo.core.analysis import CriticResult
from dovideo.core.llm import (
    EmbeddingUnavailableError,
    LLMClient,
    PermanentLLMError,
    RetriableLLMError,
    StructuredParseError,
    structured_chat,
)
from dovideo.core.models import VideoChunk, VideoContext, VideoSegment
from dovideo.core.prompts import render_segments, retrieval_intent_messages
from dovideo.core.telemetry import Telemetry
from dovideo.core.textnorm import clip, term_score
from dovideo.retrieval.store import CompositeVectorStore, cosine

FALLBACK_KEYWORD_LIMIT = 8


class RetrievalIntent(BaseModel):
    semantic_query: str = ""
    keywords: list[str] = []
    visual_keywords: list[str] = []


def _fallback_intent(goal: str) -> RetrievalIntent:
    words = [w for w in re.split(r"[,,。.!??::;、\s]+", goal) if len(w) >= 2]
    return RetrievalIntent(
        semantic_query=goal, keywords=words[:FALLBACK_KEYWORD_LIMIT], visual_keywords=[]
    )


async def plan_intent(
    llm: LLMClient, goal: str, *, settings: Settings, telemetry: Telemetry, budget
) -> RetrievalIntent:
    try:
        payload = await structured_chat(
            llm,
            retrieval_intent_messages(goal),
            timeout_s=settings.llm_timeout_seconds,
            budget=budget,
            telemetry=telemetry,
        )
        intent = RetrievalIntent(
            semantic_query=str(payload.get("semanticQuery", "")).strip() or goal,
            keywords=[str(k) for k in payload.get("keywords", []) if str(k).strip()],
            visual_keywords=[str(k) for k in payload.get("visualKeywords", []) if str(k).strip()],
        )
        if not intent.keywords and not intent.visual_keywords:
            raise StructuredParseError("意图为空")
        return intent
    except (RetriableLLMError, PermanentLLMError, StructuredParseError):
        telemetry.incr("intentFallbacks")
        return _fallback_intent(goal)


def rank_chunks(
    chunks: list[VideoChunk],
    intent: RetrievalIntent,
    query_embedding: list[float],
    qdrant_hits: list[tuple[str, float]],
) -> list[tuple[int, float]]:
    qdrant_scores = dict(qdrant_hits)
    scored: list[tuple[int, float]] = []
    for i, chunk in enumerate(chunks):
        if query_embedding and qdrant_scores:
            semantic = qdrant_scores.get(chunk.key, 0.0)
        elif query_embedding and chunk.embedding:
            semantic = cosine(query_embedding, chunk.embedding)
        else:
            semantic = 0.0
        transcripts = [c.segment_summary for c in chunks]
        # keywords 匹配范围: 摘要 + keywords + 全部 transcript(保守取本 chunk 摘要 + 全局 keywords)
        term = term_score(
            intent.keywords, chunk.segment_summary, " ".join(chunk.keywords)
        )
        visual = term_score(intent.visual_keywords, chunk.segment_summary)
        score = semantic * 0.6 + term * 0.25 + visual * 0.15
        scored.append((i, score))
    scored.sort(key=lambda kv: kv[1], reverse=True)
    return scored


def rank_segments(
    context: VideoContext,
    chunks: list[VideoChunk],
    ranked_chunks: list[tuple[int, float]],
    intent: RetrievalIntent,
) -> list[tuple[int, float]]:
    chunk_score_by_index: dict[int, float] = {}
    for ci, score in ranked_chunks:
        for seg_idx in chunks[ci].segment_indexes:
            chunk_score_by_index[seg_idx] = max(chunk_score_by_index.get(seg_idx, 0.0), score)
    scored: list[tuple[int, float]] = []
    for idx, seg in enumerate(context.segments):
        base = chunk_score_by_index.get(idx, 0.0)
        transcript_score = term_score(intent.keywords, seg.transcript)
        visual_score = term_score(intent.visual_keywords, " ".join(seg.ocr_texts))
        score = base * 0.55 + transcript_score * 0.25 + visual_score * 0.20
        scored.append((idx, score))
    # 分数降序,startMs 次序打破平手
    scored.sort(key=lambda kv: (-kv[1], context.segments[kv[0]].start_ms))
    return scored


def pack_segments(
    context: VideoContext, ranked: list[tuple[int, float]], max_chars: int
) -> list[VideoSegment]:
    """贪心装包:按排序优先级装入,超预算跳过;第一条(最高优先)永远保留;最后按 startMs 排序。"""
    chosen: list[int] = []
    used = 0
    for idx, _score in ranked:
        seg = context.segments[idx]
        size = len(seg.transcript) + sum(len(t) for t in seg.ocr_texts)
        if not chosen:
            chosen.append(idx)
            used += size
            continue
        if used + size + 1 > max_chars:
            continue
        chosen.append(idx)
        used += size + 1
    chosen.sort(key=lambda i: context.segments[i].start_ms)
    return [context.segments[i] for i in chosen]


async def _query_embedding(llm: LLMClient, semantic_query: str) -> list[float]:
    try:
        vectors = await llm.embed([semantic_query])
        return vectors[0] if vectors else []
    except EmbeddingUnavailableError:
        return []


async def select_relevant(
    context: VideoContext,
    chunks: list[VideoChunk],
    goal: str,
    *,
    llm: LLMClient,
    settings: Settings,
    telemetry: Telemetry,
    budget,
    store: CompositeVectorStore | None = None,
    media_id: str = "",
) -> list[VideoSegment]:
    """选相关上下文;≤5 分钟短视频直通(跳过分块检索,全量过预算)。"""
    if context.segments and max(s.end_ms for s in context.segments) <= settings.chunk_ms:
        telemetry.incr("shortVideoPassthroughs")
        ranked = [(i, 0.0) for i in range(len(context.segments))]
        return pack_segments(context, ranked, settings.max_context_chars)

    intent = await plan_intent(llm, goal, settings=settings, telemetry=telemetry, budget=budget)
    query_emb = await _query_embedding(llm, intent.semantic_query)

    qdrant_hits: list[tuple[str, float]] = []
    used_fallback = False
    if store is not None:
        qdrant_hits, used_fallback = await store.query(
            media_id, query_emb, settings.top_k * 2
        )
        if used_fallback:
            telemetry.incr("vectorStoreFallbacks")

    ranked_chunks = rank_chunks(chunks, intent, query_emb, qdrant_hits)
    top_chunks = ranked_chunks[: settings.top_k]
    ranked_segments = rank_segments(context, chunks, top_chunks, intent)
    return pack_segments(context, ranked_segments, settings.max_context_chars)


def near_segments(
    context: VideoContext, timestamps: list[int], margin_ms: int | None = None
) -> list[tuple[int, int]]:
    """捞 requiredTimestamps 贴近的段:margin = max(60_000, segment 自身长度)。"""
    windows: list[tuple[int, int]] = []
    for ts in timestamps:
        for seg in context.segments:
            margin = margin_ms or max(60_000, seg.end_ms - seg.start_ms)
            if seg.start_ms - margin <= ts < seg.end_ms + margin:
                windows.append((seg.start_ms, seg.end_ms))
    return sorted(set(windows))


def requires_evidence_refresh(critique: CriticResult) -> bool:
    """rewrite-only 与需要补证据的区分(原版 requiresEvidenceRefresh)。"""
    return bool(
        critique.required_timestamps
        or critique.missing_requirements
        or critique.unsupported_claims
    )


async def context_for_retry(
    full_context: VideoContext,
    current_selected: list[VideoSegment],
    critique: CriticResult,
    goal: str,
    *,
    llm: LLMClient,
    settings: Settings,
    telemetry: Telemetry,
    budget,
    chunks: list[VideoChunk] | None = None,
    store: CompositeVectorStore | None = None,
    media_id: str = "",
) -> list[VideoSegment]:
    """Critic 反馈 → 定向补证据:邻近时间戳段 ∪ critiqueQuery 重检索 ∪ 原选中集,去重合并。"""
    if not requires_evidence_refresh(critique):
        return current_selected  # rewrite-only:复用原上下文重写

    picked: dict[tuple[int, int], VideoSegment] = {
        (s.start_ms, s.end_ms): s for s in current_selected
    }

    # 1) requiredTimestamps 邻近段
    for start, end in near_segments(full_context, critique.required_timestamps):
        for seg in full_context.segments:
            if (seg.start_ms, seg.end_ms) == (start, end):
                picked[(start, end)] = seg

    # 2) critiqueQuery = goal + feedback + missing + unsupported 再跑一遍混合检索
    critique_query = "\n".join(
        [goal, *critique.feedback, *critique.missing_requirements, *critique.unsupported_claims]
    )
    chunks = chunks or []
    intent = await plan_intent(llm, critique_query, settings=settings, telemetry=telemetry, budget=budget)
    query_emb = await _query_embedding(llm, intent.semantic_query)
    qdrant_hits: list[tuple[str, float]] = []
    if store is not None:
        qdrant_hits, used_fallback = await store.query(media_id, query_emb, settings.top_k * 2)
        if used_fallback:
            telemetry.incr("vectorStoreFallbacks")
    if chunks:
        ranked_chunks = rank_chunks(chunks, intent, query_emb, qdrant_hits)
        ranked_segments = rank_segments(full_context, chunks, ranked_chunks[: settings.top_k], intent)
        for idx, _score in ranked_segments[: settings.top_k * 2]:
            seg = full_context.segments[idx]
            picked[(seg.start_ms, seg.end_ms)] = seg

    merged = sorted(picked.values(), key=lambda s: s.start_ms)
    return pack_segments(VideoContext(segments=merged), list(enumerate(merged)), settings.max_context_chars)


async def evidence_search(
    context: VideoContext,
    chunks: list[VideoChunk],
    query: str,
    *,
    llm: LLMClient,
    settings: Settings,
    telemetry: Telemetry,
    budget,
    store: CompositeVectorStore | None = None,
    media_id: str = "",
) -> list[dict]:
    """面向用户的证据检索,≤8 条(原版 search())。"""
    intent = await plan_intent(llm, query, settings=settings, telemetry=telemetry, budget=budget)
    query_emb = await _query_embedding(llm, intent.semantic_query)
    qdrant_hits: list[tuple[str, float]] = []
    if store is not None:
        qdrant_hits, used_fallback = await store.query(media_id, query_emb, settings.top_k * 2)
        if used_fallback:
            telemetry.incr("vectorStoreFallbacks")
    ranked_segments = rank_segments(context, chunks, rank_chunks(chunks, intent, query_emb, qdrant_hits), intent)

    hits: list[dict] = []
    for idx, score in ranked_segments:
        if len(hits) >= settings.max_user_hits:
            break
        seg = context.segments[idx]
        transcript_s = term_score(intent.keywords, seg.transcript)
        visual_s = term_score(intent.visual_keywords, " ".join(seg.ocr_texts))
        if visual_s > transcript_s and seg.ocr_texts:
            source, snippet = "OCR", seg.ocr_texts[0]
        elif seg.transcript:
            source = "ASR+OCR" if seg.ocr_texts else "ASR"
            snippet = seg.transcript
        elif seg.ocr_texts:
            source, snippet = "OCR", seg.ocr_texts[0]
        else:
            continue
        hits.append(
            {
                "startMs": seg.start_ms,
                "endMs": seg.end_ms,
                "source": source,
                "snippet": clip(snippet.replace("\n", " "), 180, ""),
                "score": round(score, 4),
            }
        )
    return hits
