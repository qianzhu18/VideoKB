"""共享测试夹具:离线设置、临时库、合成上下文。"""

from __future__ import annotations

import pytest

from dovideo.checkpoints.store import CheckpointStore
from dovideo.config import Settings
from dovideo.context.pipeline import merge_windows
from dovideo.core.models import FrameItem, TranscriptSegment


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        db_path=str(tmp_path / "test.db"),
        agent_max_rounds=2,
        max_context_chars=24_000,
    )


@pytest.fixture
def store(settings) -> CheckpointStore:
    s = CheckpointStore(settings.db_path)
    yield s
    s.close()


TRANSCRIPTS = [
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


@pytest.fixture
def context():
    segments = [
        TranscriptSegment(start_ms=i * 60_000, end_ms=(i + 1) * 60_000, text=t)
        for i, t in enumerate(TRANSCRIPTS)
    ]
    frames = [
        FrameItem(timestamp_ms=60_000, url="f0#timestampMs=60000", ocr_text="前序遍历:根 -> 左 -> 右"),
        FrameItem(timestamp_ms=300_000, url="f1#timestampMs=300000", ocr_text="BST: left < root < right"),
        FrameItem(timestamp_ms=480_000, url="f2#timestampMs=480000", ocr_text="AVL rotate"),
    ]
    from dovideo.core.models import VideoContext

    return VideoContext(
        source="demo://lecture",
        user_goal="帮我梳理这节课的知识点并出几道自测题",
        segments=merge_windows(segments, frames),
    )
