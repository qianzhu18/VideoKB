"""60s 窗口合并 + 帧抽取纯函数。"""

from dovideo.context.frames import (
    SCENE_SELECT_FILTER,
    build_scene_detect_cmd,
    dhash,
    frame_timestamps,
    hamming_distance,
    is_duplicate_frame,
    parse_pts_times,
)
from dovideo.context.pipeline import merge_windows
from dovideo.core.models import FrameItem, TranscriptSegment


def test_merge_windows_groups_by_60s():
    transcripts = [
        TranscriptSegment(start_ms=0, end_ms=60_000, text="第一段"),
        TranscriptSegment(start_ms=60_000, end_ms=120_000, text="第二段"),
        TranscriptSegment(start_ms=90_000, end_ms=150_000, text="第二段补充"),
    ]
    frames = [
        FrameItem(timestamp_ms=100_000, url="a.jpg#timestampMs=100000", ocr_text="标题A"),
        FrameItem(timestamp_ms=100_500, url="b.jpg#timestampMs=100500", ocr_text="标题B"),
    ]
    segments = merge_windows(transcripts, frames)
    assert [s.start_ms for s in segments] == [0, 60_000]
    assert segments[0].transcript == "第一段"
    assert segments[1].transcript == "第二段\n第二段补充"
    assert segments[1].ocr_texts == ["标题A", "标题B"]
    assert segments[1].evidence_frames == ["a.jpg#timestampMs=100000", "b.jpg#timestampMs=100500"]


def test_merge_windows_empty_text_skipped():
    segments = merge_windows([TranscriptSegment(start_ms=0, end_ms=60_000, text="  ")], [])
    assert segments == []


def test_parse_pts_times():
    log = "frame:0 pts:0 pts_time:0.04\nframe:1 pts:9000 pts_time:31.5"
    assert parse_pts_times(log) == [40, 31_500]


def test_frame_timestamps_fallback_on_missing_pts():
    # 只解析到 1 个,其余按 i*30s 兜底
    ts = frame_timestamps("pts_time:2.0", 3)
    assert ts == [2000, 30_000, 60_000]


def test_scene_detect_filter_expression():
    cmd = build_scene_detect_cmd("v.mp4", "f_%06d.jpg")
    assert SCENE_SELECT_FILTER in cmd
    assert cmd[-1] == "f_%06d.jpg"
    assert "-vsync" in cmd and "vfr" in cmd


def _gray(rows_values):
    return [[v for v in row] for row in rows_values]


def test_dhash_duplicate_detection():
    img1 = _gray([[10, 20, 30, 40, 50, 60, 70, 80, 90]] * 8)
    img2 = _gray([[10, 20, 30, 40, 50, 60, 70, 80, 91]] * 8)  # 仅最右列差 1 → 相邻比较不变
    h1, h2 = dhash(img1), dhash(img2)
    assert hamming_distance(h1, h2) == 0
    assert is_duplicate_frame(h1, h2)

    img3 = _gray([[90, 80, 70, 60, 50, 40, 30, 20, 10]] * 8)  # 完全反转 → 全部位翻转
    h3 = dhash(img3)
    assert hamming_distance(h1, h3) == 64
    assert not is_duplicate_frame(h1, h3)


def test_first_frame_never_duplicate():
    assert not is_duplicate_frame(None, 12345)
