"""5 分钟分块与混合打分。"""

import pytest

from dovideo.core.fake import FakeLLM
from dovideo.core.llm import RetriableLLMError
from dovideo.core.models import VideoContext, VideoSegment
from dovideo.core.telemetry import Telemetry
from dovideo.retrieval.chunking import build_chunks
from dovideo.retrieval.search import (
    RetrievalIntent,
    pack_segments,
    plan_intent,
    rank_chunks,
    rank_segments,
    select_relevant,
)

FAKE_SUMMARY = {"segmentSummary": "讲解二叉树遍历", "keywords": ["遍历"]}


def _seg(start_min: int, transcript: str, ocr: list[str] | None = None) -> VideoSegment:
    return VideoSegment(
        start_ms=start_min * 60_000,
        end_ms=(start_min + 1) * 60_000,
        transcript=transcript,
        ocr_texts=ocr or [],
    )


async def test_build_chunks_five_minute_boundaries(settings, context):
    llm = FakeLLM(summaries=[FAKE_SUMMARY])
    chunks = await build_chunks(context, settings=settings, llm=llm, telemetry=Telemetry(), budget=None)
    # context 覆盖 0–10 分钟 → [0,5min) 与 [5min,10min) 两块
    assert [c.start_ms for c in chunks] == [0, 300_000]
    assert chunks[0].segment_indexes and chunks[1].segment_indexes
    assert all(c.segment_summary for c in chunks)


async def test_build_chunks_summary_fallback_on_llm_failure(settings, context):
    llm = FakeLLM(summaries=[FAKE_SUMMARY])
    llm.fail_next = RetriableLLMError("模拟摘要服务抖动")
    telemetry = Telemetry()
    chunks = await build_chunks(context, settings=settings, llm=llm, telemetry=telemetry, budget=None)
    assert telemetry.counters["summaryFallbacks"] == 1
    assert chunks[0].segment_summary  # 降级为原文头 500 字


async def test_plan_intent_fallback_tokenization(settings):
    llm = FakeLLM()
    telemetry = Telemetry()
    intent = await plan_intent(llm, "总结前序遍历与平衡树,给出自测题", settings=settings, telemetry=telemetry, budget=None)
    assert telemetry.counters["intentFallbacks"] == 1
    assert "前序遍历" in intent.keywords or any("遍历" in k for k in intent.keywords)


def test_rank_chunks_weights():
    chunks = [
        type("C", (), {"key": "0:300000", "segment_summary": "讲解二叉树遍历", "keywords": ["遍历"], "embedding": []})(),
        type("C", (), {"key": "300000:600000", "segment_summary": "介绍如何做菜", "keywords": ["烹饪"], "embedding": []})(),
    ]
    intent = RetrievalIntent(semantic_query="遍历", keywords=["遍历"], visual_keywords=[])
    ranked = rank_chunks(chunks, intent, query_embedding=[], qdrant_hits=[])
    assert ranked[0][0] == 0  # 语义/向量缺失时关键词命中决定排序


def test_rank_segments_prefers_keyword_hits(context):
    from dovideo.core.models import VideoChunk

    chunks = [
        VideoChunk(start_ms=0, end_ms=300_000, segment_summary="遍历", keywords=["遍历"], segment_indexes=[0, 1, 2]),
        VideoChunk(start_ms=300_000, end_ms=600_000, segment_summary="BST", keywords=["BST"], segment_indexes=[3, 4, 5]),
    ]
    intent = RetrievalIntent(semantic_query="遍历", keywords=["遍历"], visual_keywords=[])
    ranked_chunks = rank_chunks(chunks, intent, [], [])
    ranked = rank_segments(context, chunks, ranked_chunks, intent)
    top_idx = ranked[0][0]
    assert "遍历" in context.segments[top_idx].transcript


def test_pack_segments_budget_first_kept():
    segs = [_seg(i, "字" * 100) for i in range(5)]
    ctx = VideoContext(segments=segs)
    ranked = [(i, 1.0 - i * 0.1) for i in range(5)]
    packed = pack_segments(ctx, ranked, max_chars=150)
    assert len(packed) == 1  # 每段 ~100 字,预算 150 只装得下第一条
    assert packed[0].start_ms == 0  # 永远保留第一条(最高优先)


async def test_short_video_passthrough_skips_intent(settings):
    llm = FakeLLM()  # RETRIEVAL_INTENT 队列为空 → 若被调用会 AssertionError
    ctx = VideoContext(segments=[_seg(0, "短视频"), _seg(1, "内容")])
    selected = await select_relevant(
        ctx, [], "目标", llm=llm, settings=settings, telemetry=Telemetry(), budget=None
    )
    assert len(selected) == 2
    assert llm.calls_for("RETRIEVAL_INTENT") == 0
