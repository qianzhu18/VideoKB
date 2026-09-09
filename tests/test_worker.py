"""任务编排:重试语义 / 永久失败台账 / 预算熔断 / 内容级复用。"""

import pytest

from dovideo.checkpoints.store import goal_digest
from dovideo.core.analysis import AnalysisMode, TaskStage
from dovideo.core.budget import BudgetExhaustedError
from dovideo.core.fake import FakeLLM
from dovideo.core.llm import RetriableLLMError
from dovideo.jobs.worker import (
    Deps,
    DuplicateTaskError,
    InlineJobRunner,
    PermanentAnalysisError,
    run_analysis_job,
)
from dovideo.core.telemetry import Telemetry

from test_loop import FAKE_INTENT, FAKE_PLAN, FAKE_SUMMARY, GOOD_RESULT


class FlakyLLM(FakeLLM):
    """第 N 次调用抛可重试异常,模拟第三方抖动。"""

    def __init__(self, fail_on_calls: set[int], **kw):
        super().__init__(**kw)
        self.fail_on_calls = fail_on_calls
        self.count = 0

    async def chat(self, messages, *, timeout_s, temperature=0.3):
        self.count += 1
        if self.count in self.fail_on_calls:
            raise RetriableLLMError("flaky upstream")
        return await super().chat(messages, timeout_s=timeout_s, temperature=temperature)


def _deps(settings, store, llm, context) -> Deps:
    async def builder(media_id: str, goal: str):
        return context

    return Deps(settings=settings, llm=llm, store=store, context_builder=builder)


async def _zero(_: float) -> None:
    return None


def _presave_context(store, context):
    store.save("m1", "media:context", context.model_dump(), stage="CONTEXT_COMPLETED")


async def test_retryable_error_retried_then_success(settings, store, context):
    # 调用次序: 摘要(1) → 意图(2) → Planner(3);在 Planner 处注入抖动
    llm = FlakyLLM(
        fail_on_calls={3},
        plans=[FAKE_PLAN],
        executes=[GOOD_RESULT],
        critiques=[{"passed": True}],
        summaries=[FAKE_SUMMARY],
        intents=[FAKE_INTENT],
    )
    runner = InlineJobRunner(_deps(settings, store, llm, context), sleep_fn=_zero)
    outcome = await runner.submit("m1", context.user_goal, AnalysisMode.LEARNING)
    assert outcome.passed is True
    assert llm.count >= 5  # 经历了至少一次完整重投
    digest = goal_digest(context.user_goal, "LEARNING")
    assert store.load_stage("m1", digest) == TaskStage.ANALYSIS_COMPLETED.value


async def test_permanent_failure_recorded(settings, store, context):
    _presave_context(store, context)
    invalid = {"understoodGoal": "g", "tasks": [f"t{i}" for i in range(9)]}
    llm = FakeLLM(
        plans=[invalid],
        repairs=[invalid],
        executes=[GOOD_RESULT],
        critiques=[{"passed": True}],
        summaries=[FAKE_SUMMARY],
        intents=[FAKE_INTENT],
    )
    runner = InlineJobRunner(_deps(settings, store, llm, context), sleep_fn=_zero)
    with pytest.raises(PermanentAnalysisError):
        await runner.submit("m1", context.user_goal, AnalysisMode.LEARNING)
    rows = store.list_failures()
    assert rows and rows[0]["stage"] in (TaskStage.FAILED.value, TaskStage.DEAD_LETTERED.value)
    digest = goal_digest(context.user_goal, "LEARNING")
    assert store.load_stage("m1", digest) in (TaskStage.FAILED.value, TaskStage.DEAD_LETTERED.value)


async def test_budget_exhausted_no_retry(settings, store, context):
    _presave_context(store, context)
    settings.agent_max_estimated_tokens = 10  # 极小预算,任何调用立即熔断
    llm = FakeLLM(plans=[FAKE_PLAN], summaries=[FAKE_SUMMARY], intents=[FAKE_INTENT])
    runner = InlineJobRunner(_deps(settings, store, llm, context), sleep_fn=_zero)
    with pytest.raises(BudgetExhaustedError):
        await runner.submit("m1", context.user_goal, AnalysisMode.LEARNING)
    digest = goal_digest(context.user_goal, "LEARNING")
    assert store.load_stage("m1", digest) == TaskStage.BUDGET_EXHAUSTED.value
    assert llm.chat_count == 0  # 预算检查在调用前熔断,不烧 Token


async def test_duplicate_submit_rejected(settings, store, context):
    digest = goal_digest(context.user_goal, "GENERAL")
    store.claim_active("m1", digest)
    llm = FakeLLM(plans=[FAKE_PLAN], summaries=[FAKE_SUMMARY], intents=[FAKE_INTENT])
    with pytest.raises(DuplicateTaskError):
        await run_analysis_job(_deps(settings, store, llm, context), "m1", context.user_goal)


async def test_content_level_context_reuse(settings, store, context):
    """换目标重跑:media:context checkpoint 命中,context_builder 只被调用一次。"""
    llm = FakeLLM(
        plans=[FAKE_PLAN],
        summaries=[FAKE_SUMMARY],
        intents=[FAKE_INTENT],
        executes=[GOOD_RESULT],
        critiques=[{"passed": True}],
    )
    calls = {"n": 0}

    async def builder(media_id: str, goal: str):
        calls["n"] += 1
        return context

    deps = Deps(settings=settings, llm=llm, store=store, context_builder=builder)
    runner = InlineJobRunner(deps, sleep_fn=_zero)
    await runner.submit("m1", "目标A", AnalysisMode.GENERAL)
    await runner.submit("m1", "目标B", AnalysisMode.GENERAL)
    assert calls["n"] == 1
