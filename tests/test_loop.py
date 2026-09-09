"""AgentLoop 闭环:护栏链 / 定向重检索 / 轮次上限 / checkpoint 恢复。"""

from dovideo.agent.loop import AgentLoop, clamp_critique, normalize_critique, structure_issues
from dovideo.checkpoints.store import goal_digest
from dovideo.core.analysis import (
    AgentPlan,
    AgentState,
    AnalysisMode,
    AnalysisResult,
    CriticResult,
    Evidence,
    ResultSection,
)
from dovideo.core.fake import FakeLLM
from dovideo.core.models import VideoContext
from dovideo.core.telemetry import Telemetry
from dovideo.retrieval.chunking import build_chunks

GOOD_RESULT = {
    "title": "二叉树课程知识点",
    "conclusions": ["前序遍历是根左右", "AVL 树通过旋转保持平衡", "课程核心是三种遍历与平衡树"],
    "evidence": [
        {"timestampMs": 60_000, "source": "ASR+OCR", "content": "前序遍历,顺序是根节点、左子树、右子树", "claim": "前序遍历是根左右"},
        {"timestampMs": 480_000, "source": "OCR", "content": "AVL rotate", "claim": "AVL 树通过旋转保持平衡"},
        {"timestampMs": 540_000, "source": "ASR", "content": "掌握三种遍历和平衡树是本章的核心", "claim": "课程核心是三种遍历与平衡树"},
    ],
    "suggestions": ["结合代码练习"],
    "sections": [
        {"key": "outline", "title": "大纲", "items": ["遍历", "BST", "AVL"]},
        {"key": "keypoints", "title": "重点", "items": ["三种遍历顺序"]},
        {"key": "quiz", "title": "自测题", "items": ["中序遍历顺序? 答案:左根右"]},
        {"key": "pitfalls", "title": "易错点", "items": ["BST 最坏退化链表"]},
    ],
}

BAD_RESULT = {
    "title": "二叉树课程知识点",
    "conclusions": ["前序遍历是根左右", "AVL 树用颜色标记保持平衡"],
    "evidence": [
        {"timestampMs": 60_000, "source": "ASR+OCR", "content": "前序遍历,顺序是根节点、左子树、右子树", "claim": "前序遍历是根左右"},
        {"timestampMs": 480_000, "source": "OCR", "content": "不存在的画面文字", "claim": "AVL 树用颜色标记保持平衡"},
    ],
    "suggestions": [],
}

FAKE_PLAN = {
    "understoodGoal": "梳理二叉树课程知识点并生成自测题",
    "tasks": ["总结三种遍历", "解释 BST 与 AVL", "生成自测题"],
}
CRITIQUE_FAIL = {
    "passed": True,  # 谎报通过 → 一致性钳制必须强制拉回
    "feedback": [],
    "missingRequirements": ["缺少自测题"],
    "unsupportedClaims": [],
    "requiredTimestamps": [480_000],
}
CRITIQUE_PASS = {"passed": True, "feedback": [], "missingRequirements": [], "unsupportedClaims": [], "requiredTimestamps": []}
FAKE_SUMMARY = {"segmentSummary": "讲解二叉树", "keywords": ["二叉树"]}
FAKE_INTENT = {"semanticQuery": "AVL 自测题", "keywords": ["AVL", "自测"], "visualKeywords": ["AVL"]}


def _make_llm(**kw) -> FakeLLM:
    return FakeLLM(
        plans=[FAKE_PLAN],
        executes=[BAD_RESULT, GOOD_RESULT],
        critiques=[CRITIQUE_FAIL, CRITIQUE_PASS],
        mode={"mode": "LEARNING", "reason": "学习"},
        summaries=[FAKE_SUMMARY],
        intents=[FAKE_INTENT],
        **kw,
    )


async def test_full_loop_two_rounds_with_guardrails(settings, store, context):
    llm = _make_llm()
    chunks = await build_chunks(context, settings=settings, llm=llm, telemetry=Telemetry(), budget=None)
    stages: list[str] = []
    loop = AgentLoop(llm=llm, store=store, settings=settings, events=lambda s, m: stages.append(s))
    outcome = await loop.run("m1", context, context.user_goal, AnalysisMode.LEARNING, chunks=chunks)

    assert outcome.passed is True
    assert outcome.rounds == 2
    assert "CRITIC_RETRY_REQUIRED" in stages
    assert "EVIDENCE_REFRESHED" in stages
    # 第一轮被纯代码核验拦截(谎报 passed 被钳制)
    assert llm.calls_for("EXECUTOR") == 2
    # 终态 checkpoint 已保存
    stage, payload = store.load("m1", f"goal:{goal_digest(context.user_goal, 'LEARNING')}:result")
    assert stage == "ANALYSIS_COMPLETED" and payload["title"]


async def test_terminal_checkpoint_reuse_zero_llm_calls(settings, store, context):
    llm = _make_llm()
    chunks = await build_chunks(context, settings=settings, llm=llm, telemetry=Telemetry(), budget=None)
    loop = AgentLoop(llm=llm, store=store, settings=settings)
    await loop.run("m1", context, context.user_goal, AnalysisMode.LEARNING, chunks=chunks)

    calls_before = llm.chat_count
    outcome2 = await AgentLoop(llm=llm, store=store, settings=settings).run(
        "m1", context, context.user_goal, AnalysisMode.LEARNING, chunks=chunks
    )
    assert llm.chat_count == calls_before  # 0 次新增调用
    assert outcome2.passed is True
    assert outcome2.rounds == 2


async def test_draft_checkpoint_resume_skips_executor(settings, store, context):
    digest = goal_digest(context.user_goal, "LEARNING")
    plan = AgentPlan.model_validate(FAKE_PLAN)
    store.save("m1", f"goal:{digest}:plan", plan.model_dump(by_alias=True), stage="PLAN_COMPLETED")
    good = AnalysisResult.model_validate(GOOD_RESULT)
    store.save(
        "m1",
        f"goal:{digest}:criticState",
        AgentState(round=1, plan=plan, result=good, critique=None).model_dump(by_alias=True),
        stage="EXECUTOR_COMPLETED",
    )
    llm = FakeLLM(critiques=[CRITIQUE_PASS])  # 草稿直接通过审校
    loop = AgentLoop(llm=llm, store=store, settings=settings)
    outcome = await loop.run("m1", context, context.user_goal, AnalysisMode.LEARNING)

    assert llm.calls_for("EXECUTOR") == 0  # 草稿恢复:跳过 Executor
    assert llm.calls_for("PLANNER") == 0  # 计划也命中 checkpoint
    assert llm.calls_for("CRITIC") == 1
    assert outcome.passed is True


async def test_plan_structure_repair(settings, store, context):
    invalid_plan = {"understoodGoal": "g", "tasks": [f"任务{i}" for i in range(6)]}  # 6 个超上限
    llm = _make_llm(repairs=[FAKE_PLAN])
    llm._queues["PLANNER"] = [invalid_plan]
    loop = AgentLoop(llm=llm, store=store, settings=settings)
    outcome = await loop.run("m1", context, context.user_goal, AnalysisMode.LEARNING)
    assert llm.calls_for("PLANNER_REPAIR") == 1
    assert outcome.passed is True


def test_clamp_forces_false_when_llm_lies(context):
    raw = normalize_critique(CRITIQUE_FAIL)
    result = AnalysisResult.model_validate(BAD_RESULT)
    from dovideo.core.analysis import get_profile

    critic = clamp_critique(raw, result=result, profile=get_profile(AnalysisMode.LEARNING), segments=context.segments)
    assert critic.passed is False  # 谎报被拉回
    assert critic.unsupported_claims  # 无证据结论
    assert 480_000 in critic.required_timestamps  # 定向重检索钩子


def test_structure_issues_requires_mode_sections():
    from dovideo.core.analysis import get_profile

    result = AnalysisResult.model_validate(GOOD_RESULT)
    assert structure_issues(result, get_profile(AnalysisMode.LEARNING)) == []
    general_only = result.model_copy(deep=True)
    general_only.sections = []
    assert structure_issues(general_only, get_profile(AnalysisMode.LEARNING))


def test_normalize_critique_defaults():
    critic = normalize_critique(None)
    assert critic.passed is False and critic.feedback
    critic2 = normalize_critique({"passed": True})
    assert critic2.feedback == [] and critic2.required_timestamps == []


def _unused(**_):  # 防止 VideoContext 导入被裁
    return VideoContext
