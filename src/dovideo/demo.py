"""离线端到端演示:无需 API Key / ffmpeg,跑通 VideoContext → 混合检索 → AgentLoop 闭环。

    cd dovideo && python -m dovideo.demo
"""

from __future__ import annotations

import asyncio
import json

from dovideo.agent.loop import AgentLoop
from dovideo.checkpoints.store import CheckpointStore, goal_digest
from dovideo.config import Settings
from dovideo.core.analysis import AnalysisMode
from dovideo.core.fake import FakeLLM
from dovideo.core.models import FrameItem, TranscriptSegment, VideoContext
from dovideo.retrieval.chunking import build_chunks
from dovideo.retrieval.search import select_relevant
from dovideo.core.telemetry import Telemetry


def synthetic_context() -> VideoContext:
    """合成一段 6 分钟的“二叉树课程”上下文(10 个 60s 窗口,含 ASR + OCR)。"""
    transcripts = [
        "大家好,今天我们开始讲解二叉树的基本概念",
        "首先介绍前序遍历,顺序是根节点、左子树、右子树",
        "中序遍历的顺序是左子树、根节点、右子树",
        "后序遍历则是左子树、右子树,最后访问根节点",
        "接下来看二叉搜索树,左子树所有节点小于根节点",
        "二叉搜索树的查找时间复杂度平均是 O(log n)",
        "最坏情况下二叉搜索树会退化成链表,复杂度 O(n)",
        "为了解决退化问题,我们引入平衡树,比如 AVL 树",
        "AVL 树通过旋转操作保持左右子树高度差不超过一",
        "最后总结:掌握三种遍历和平衡树是本章的核心",
    ]
    segments = [
        TranscriptSegment(start_ms=i * 60_000, end_ms=(i + 1) * 60_000, text=t)
        for i, t in enumerate(transcripts)
    ]
    frames = [
        FrameItem(timestamp_ms=60_000, url="frame_000060.jpg#timestampMs=60000", ocr_text="前序遍历:根 -> 左 -> 右"),
        FrameItem(timestamp_ms=300_000, url="frame_000300.jpg#timestampMs=300000", ocr_text="BST: left < root < right"),
        FrameItem(timestamp_ms=480_000, url="frame_000480.jpg#timestampMs=480000", ocr_text="AVL rotate"),
    ]
    from dovideo.context.pipeline import merge_windows

    return VideoContext(
        source="demo://binary-tree-lecture",
        user_goal="帮我梳理这节课的知识点并出几道自测题",
        segments=merge_windows(segments, frames),
    )


FAKE_PLAN = {
    "understoodGoal": "梳理二叉树课程知识点并生成自测题",
    "tasks": ["总结三种遍历方式", "解释二叉搜索树与平衡树", "生成自测题"],
}

FAKE_EXECUTE_BAD = {
    "title": "二叉树课程知识点",
    "conclusions": ["前序遍历是根左右", " AVL 树用颜色标记保持平衡"],
    "evidence": [
        {"timestampMs": 60_000, "source": "ASR+OCR", "content": "前序遍历,顺序是根节点、左子树、右子树", "claim": "前序遍历是根左右"},
        {"timestampMs": 480_000, "source": "OCR", "content": "不存在的画面文字", "claim": " AVL 树用颜色标记保持平衡"},
    ],
    "suggestions": ["结合代码练习三种遍历"],
}

FAKE_CRITIQUE_FAIL = {
    "passed": True,  # 故意报 passed —— 纯代码核验必须强制拉回 false(一致性钳制)
    "feedback": [],
    "missingRequirements": ["缺少自测题"],
    "unsupportedClaims": [],
    "requiredTimestamps": [480_000],
}

FAKE_EXECUTE_GOOD = {
    "title": "二叉树课程知识点",
    "conclusions": ["前序遍历是根左右", "AVL 树通过旋转保持平衡", "课程核心是三种遍历与平衡树"],
    "evidence": [
        {"timestampMs": 60_000, "source": "ASR+OCR", "content": "前序遍历,顺序是根节点、左子树、右子树", "claim": "前序遍历是根左右"},
        {"timestampMs": 480_000, "source": "OCR", "content": "AVL rotate", "claim": "AVL 树通过旋转保持平衡"},
        {"timestampMs": 540_000, "source": "ASR", "content": "掌握三种遍历和平衡树是本章的核心", "claim": "课程核心是三种遍历与平衡树"},
    ],
    "suggestions": ["结合代码练习三种遍历"],
    "sections": [
        {"key": "outline", "title": "大纲", "items": ["遍历", "BST", "AVL"]},
        {"key": "keypoints", "title": "重点", "items": ["三种遍历顺序", "BST 复杂度"]},
        {"key": "quiz", "title": "自测题", "items": ["中序遍历的顺序是什么?答案:左根右"]},
        {"key": "pitfalls", "title": "易错点", "items": ["BST 最坏退化为链表"]},
    ],
}

FAKE_CRITIQUE_PASS = {"passed": True, "feedback": [], "missingRequirements": [], "unsupportedClaims": [], "requiredTimestamps": []}

FAKE_SUMMARY = {"segmentSummary": "课程讲解二叉树遍历与平衡树", "keywords": ["二叉树", "遍历", "AVL"]}
FAKE_INTENT = {"semanticQuery": "二叉树遍历与平衡树知识点", "keywords": ["遍历", "平衡树"], "visualKeywords": ["AVL"]}


async def main() -> None:
    settings = Settings(db_path=":memory:")
    # :memory: 每个连接独立,这里用临时文件替代以保持同一库
    import tempfile, os

    fd, db_path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    settings.db_path = db_path
    store = CheckpointStore(db_path)

    llm = FakeLLM(
        plans=[FAKE_PLAN],
        executes=[FAKE_EXECUTE_BAD, FAKE_EXECUTE_GOOD],
        critiques=[FAKE_CRITIQUE_FAIL, FAKE_CRITIQUE_PASS],
        mode={"mode": "LEARNING", "reason": "学习课程知识点"},
        summaries=[FAKE_SUMMARY],
        intents=[FAKE_INTENT],
    )

    context = synthetic_context()
    telemetry = Telemetry()

    print("== 1. VideoContext ==")
    print(f"segments={len(context.segments)}, 首段: {context.segments[0].model_dump()}")

    print("\n== 2. 检索分块 + 混合选择(降级路径:无 embedding/无 Qdrant)==")
    chunks = await build_chunks(context, settings=settings, llm=llm, telemetry=telemetry, budget=None)
    selected = await select_relevant(
        context, chunks, context.user_goal,
        llm=llm, settings=settings, telemetry=telemetry, budget=None,
    )
    print(f"chunks={len(chunks)}, selected={len(selected)} 段")

    print("\n== 3. AgentLoop(两轮:第一轮被纯代码核验拦截 → 定向补证据 → 第二轮通过)==")
    stages: list[str] = []
    loop = AgentLoop(
        llm=llm,
        store=store,
        settings=settings,
        telemetry=telemetry,
        events=lambda stage, message: stages.append(stage),
    )
    outcome = await loop.run(
        "demo-media", context, context.user_goal, AnalysisMode.LEARNING, selected=selected, chunks=chunks
    )
    print(f"passed={outcome.passed}, rounds={outcome.rounds}")
    print(f"stages: {' -> '.join(outcome.stage_history)}")
    print(f"sections: {[s.key for s in outcome.result.sections]}")
    print(f"telemetry: {json.dumps(outcome.telemetry, ensure_ascii=False)}")

    print("\n== 4. 终态 checkpoint 复用(第二次运行 0 次 LLM 调用)==")
    calls_before = llm.chat_count
    outcome2 = await AgentLoop(
        llm=llm, store=store, settings=settings, telemetry=Telemetry()
    ).run("demo-media", context, context.user_goal, AnalysisMode.LEARNING, selected=selected, chunks=chunks)
    print(f"第二次运行 LLM 调用增量 = {llm.chat_count - calls_before}(应为 0),passed={outcome2.passed}")

    digest = goal_digest(context.user_goal, "LEARNING")
    assert llm.chat_count - calls_before == 0, "终态 checkpoint 未命中!"
    assert "EVIDENCE_REFRESHED" in stages or "CRITIC_RETRY_REQUIRED" in stages, "护栏链未生效!"
    print("\n✅ 离线闭环演示通过:全部核心机制(护栏/定向重检索/轮次/checkpoint)按设计工作")


if __name__ == "__main__":
    asyncio.run(main())
