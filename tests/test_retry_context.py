"""定向重检索:Critic 反馈 → 证据回流(移植自 contextForRetry / refineForCritique)。"""

from dovideo.core.analysis import CriticResult
from dovideo.core.fake import FakeLLM
from dovideo.core.telemetry import Telemetry
from dovideo.retrieval.search import (
    context_for_retry,
    near_segments,
    requires_evidence_refresh,
)

from test_loop import FAKE_INTENT


def test_requires_evidence_refresh():
    assert requires_evidence_refresh(CriticResult(passed=False, feedback=["改写结论"])) is False
    assert requires_evidence_refresh(
        CriticResult(passed=False, required_timestamps=[1000])
    )
    assert requires_evidence_refresh(
        CriticResult(passed=False, missing_requirements=["缺自测题"])
    )


def test_near_segments_margin(context):
    # margin = max(60s, 段长);480000 命中 [480000,540000) 及相邻窗口
    windows = near_segments(context, [480_000])
    assert (480_000, 540_000) in windows


async def test_context_for_retry_merges_near_and_current(settings, context):
    selected_before = context.segments[:3]
    critique = CriticResult(
        passed=False,
        feedback=["补充 AVL 相关证据"],
        required_timestamps=[480_000],
    )
    llm = FakeLLM(intents=[FAKE_INTENT])
    merged = await context_for_retry(
        context, selected_before, critique, context.user_goal,
        llm=llm, settings=settings, telemetry=Telemetry(), budget=None,
    )
    merged_keys = {(s.start_ms, s.end_ms) for s in merged}
    # 原选中集保留
    for seg in selected_before:
        assert (seg.start_ms, seg.end_ms) in merged_keys
    # Critic 钩子时间戳的邻近段被补入
    assert (480_000, 540_000) in merged_keys
    # 按 startMs 排序
    starts = [s.start_ms for s in merged]
    assert starts == sorted(starts)


async def test_context_for_retry_rewrite_only(settings, context):
    critique = CriticResult(passed=False, feedback=["换个说法重写结论"])
    selected = context.segments[:3]
    merged = await context_for_retry(
        context, selected, critique, context.user_goal,
        llm=FakeLLM(), settings=settings, telemetry=Telemetry(), budget=None,
    )
    assert merged == selected  # 无需补证据 → 原样复用,LLM 零调用
