"""纯代码证据核验:Critic 的确定性护栏。"""

from dovideo.core.analysis import AnalysisResult, Evidence
from dovideo.core.evidence import evidence_supported, verify_result
from dovideo.core.models import VideoSegment


SEGS = [
    VideoSegment(
        start_ms=60_000,
        end_ms=120_000,
        transcript="首先介绍前序遍历,顺序是根节点、左子树、右子树",
        ocr_texts=["前序遍历:根 -> 左 -> 右"],
    ),
    VideoSegment(
        start_ms=480_000,
        end_ms=540_000,
        transcript="AVL 树通过旋转操作保持平衡",
        ocr_texts=["AVL rotate"],
    ),
]


def test_supported_asr_substring():
    ev = Evidence(timestamp_ms=60_000, source="ASR", content="前序遍历,顺序是根节点、左子树、右子树", claim="x")
    assert evidence_supported(ev, SEGS)


def test_supported_ocr_with_punctuation_noise():
    ev = Evidence(timestamp_ms=60_000, source="OCR", content="前序遍历:根 -> 左 -> 右", claim="x")
    assert evidence_supported(ev, SEGS)


def test_unsupported_timestamp_out_of_range():
    ev = Evidence(timestamp_ms=200_000, source="ASR", content="前序遍历", claim="x")
    assert not evidence_supported(ev, SEGS)


def test_unsupported_content_not_in_source():
    ev = Evidence(timestamp_ms=60_000, source="ASR", content="这段话根本不在视频里", claim="x")
    assert not evidence_supported(ev, SEGS)


def test_unsupported_source_mismatch():
    # OCR 证据却引用 ASR 文本 → 不通过
    ev = Evidence(timestamp_ms=480_000, source="OCR", content="AVL 树通过旋转操作保持平衡", claim="x")
    assert not evidence_supported(ev, SEGS)


def test_verify_result_claim_binding_and_coverage():
    result = AnalysisResult(
        conclusions=["前序遍历是根左右", "AVL 用旋转保持平衡"],
        evidence=[
            Evidence(timestamp_ms=60_000, source="ASR", content="前序遍历,顺序是根节点、左子树、右子树", claim="前序遍历是根左右"),
            # claim 与任何结论都不一致
            Evidence(timestamp_ms=60_000, source="ASR", content="首先介绍前序遍历", claim="无关的结论"),
        ],
    )
    report = verify_result(result, SEGS)
    # 第二条证据 claim 不匹配
    assert report.claim_mismatches
    # 第二条结论无证据覆盖
    assert any("AVL" in m for m in report.uncovered_conclusions)
    assert not report.ok


def test_verify_result_all_good():
    result = AnalysisResult(
        conclusions=["前序遍历是根左右"],
        evidence=[Evidence(timestamp_ms=60_000, source="ASR", content="首先介绍前序遍历", claim="前序遍历是根左右")],
    )
    report = verify_result(result, SEGS)
    assert report.ok
