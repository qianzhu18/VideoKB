"""关键帧抽取(移植自 utils/OcrUtils.java + VideoContextService OCR 分支)。

- FFmpeg 场景检测:首帧 + 场景变化分>0.35 + 距上次选中≥30s 保底采样
- 时间戳从 showinfo 日志的 pts_time: 正则提取,丢失按 i*30s 兜底
- dHash 感知哈希去重:9×8 灰度 → 64-bit,汉明距离 ≤5 判重
"""

from __future__ import annotations

import re

SCENE_SELECT_FILTER = "select=eq(n\\,0)+gt(scene\\,0.35)+gte(t-prev_selected_t\\,30),showinfo"

PTS_TIME_RE = re.compile(r"pts_time:([0-9]+\.?[0-9]*)")
DEFAULT_FALLBACK_INTERVAL_MS = 30_000
DUPLICATE_HAMMING_THRESHOLD = 5


def build_scene_detect_cmd(
    video_path: str, output_pattern: str, ffmpeg_bin: str = "ffmpeg"
) -> list[str]:
    return [
        ffmpeg_bin,
        "-y",
        "-i",
        video_path,
        "-vf",
        SCENE_SELECT_FILTER,
        "-vsync",
        "vfr",
        output_pattern,
    ]


def parse_pts_times(log: str) -> list[int]:
    """从 showinfo 日志提取 pts_time → 毫秒(原版丢失时按 i*30s 兜底)。"""
    return [int(round(float(m) * 1000)) for m in PTS_TIME_RE.findall(log)]


def frame_timestamps(log: str, count: int) -> list[int]:
    parsed = parse_pts_times(log)
    if len(parsed) >= count:
        return parsed[:count]
    return parsed + [
        (len(parsed) + i) * DEFAULT_FALLBACK_INTERVAL_MS for i in range(count - len(parsed))
    ]


def dhash(gray: list[list[int]]) -> int:
    """差异哈希:逐行比较左邻像素亮度(输入 H×W 灰度矩阵,典型 8×9 → 64-bit)。"""
    bits = 0
    for row in gray:
        for i in range(len(row) - 1):
            bits = (bits << 1) | (1 if row[i] > row[i + 1] else 0)
    return bits


def hamming_distance(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


def is_duplicate_frame(previous_hash: int | None, current_hash: int) -> bool:
    """与上一帧汉明距离 ≤5 视为重复(原版 Long.bitCount(prev ^ cur) <= 5)。"""
    if previous_hash is None:
        return False
    return hamming_distance(previous_hash, current_hash) <= DUPLICATE_HAMMING_THRESHOLD


def image_dhash(image_path: str) -> int:
    """从图片文件计算 dHash(需要 Pillow,仅在实际运行时使用)。"""
    try:
        from PIL import Image
    except ImportError as exc:  # pragma: no cover
        raise RuntimeError("需要安装 Pillow 才能从图片计算 dHash") from exc
    with Image.open(image_path) as img:
        small = img.convert("L").resize((9, 8))
        gray = list(small.getdata())
        rows = [gray[i * 9 : (i + 1) * 9] for i in range(8)]
    return dhash(rows)
