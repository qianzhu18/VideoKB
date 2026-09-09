"""VideoContext 流水线(移植自 service/VideoContextService.java)。

双分支并行 + 单路失败容忍:
- ASR 分支:60s 切片逐片转写(单片失败计数容忍,全部失败才抛且带 cause)
- 关键帧分支:场景检测选帧 → dHash 去重 → 逐帧 OCR(单帧失败容忍)
- 双路皆败才抛 PipelineError(主异常 + suppressed)
- 合并:60s 固定窗口,transcript 按序拼接、ocr_texts/frames 收集
"""

from __future__ import annotations

import asyncio
import logging
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from dovideo.config import Settings
from dovideo.context import asr as asr_mod
from dovideo.context import frames as frames_mod
from dovideo.core.models import FrameItem, TranscriptSegment, VideoContext, VideoSegment

logger = logging.getLogger(__name__)


class PipelineError(Exception):
    """双路皆败;携带主异常与被抑制异常。"""

    def __init__(self, message: str, *, cause: Exception, suppressed: Exception | None = None):
        super().__init__(message)
        self.__cause__ = cause
        self.suppressed = suppressed


@dataclass(slots=True)
class BranchResult:
    items: list = field(default_factory=list)
    error: Exception | None = None


def merge_windows(
    transcripts: list[TranscriptSegment],
    frames: list[FrameItem],
    window_ms: int = 60_000,
) -> list[VideoSegment]:
    """纯函数:固定窗口合并(原版 TreeMap<Long, SegmentBuilder>,窗口键 = ts/window*window)。"""
    builders: dict[int, dict] = {}

    def _builder_for(ts: int) -> dict:
        key = ts // window_ms * window_ms
        if key not in builders:
            builders[key] = {
                "start_ms": key,
                "end_ms": key + window_ms,
                "transcripts": [],
                "ocr_texts": [],
                "evidence_frames": [],
            }
        return builders[key]

    for t in transcripts:
        if t.text.strip():
            _builder_for(t.start_ms)["transcripts"].append(t.text)
    for f in frames:
        b = _builder_for(f.timestamp_ms)
        if f.ocr_text.strip():
            b["ocr_texts"].append(f.ocr_text)
        if f.url:
            b["evidence_frames"].append(f.url)

    segments = [
        VideoSegment(
            start_ms=b["start_ms"],
            end_ms=b["end_ms"],
            transcript="\n".join(b["transcripts"]),
            ocr_texts=b["ocr_texts"],
            evidence_frames=b["evidence_frames"],
        )
        for _, b in sorted(builders.items())
    ]
    return segments


async def _run_subprocess(cmd: list[str], timeout_s: float) -> str:
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    try:
        stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
    except asyncio.TimeoutError:
        proc.kill()
        raise
    if proc.returncode != 0:
        raise RuntimeError(f"命令失败({proc.returncode}): {' '.join(cmd[:3])}…")
    return stdout.decode("utf-8", errors="replace")


async def _asr_branch(
    video_path: str, work_dir: Path, settings: Settings, http: "object | None"
) -> BranchResult:
    failures: list[Exception] = []
    try:
        work_dir.mkdir(parents=True, exist_ok=True)
        log = await _run_subprocess(
            asr_mod.audio_slice_cmd(str(video_path), str(work_dir), settings.ffmpeg_bin),
            timeout_s=900,
        )
        logger.debug("audio slice log: %s", log[-500:])
    except Exception as exc:
        return BranchResult(error=exc)

    slices = sorted(work_dir.glob("audio_*.mp3"))
    if not slices:
        return BranchResult(error=RuntimeError(f"音频切片输出为空: {work_dir}"))

    transcripts: list[TranscriptSegment] = []
    import httpx as _httpx

    async with _httpx.AsyncClient() as client:
        for i, audio in enumerate(slices):
            start = i * 60_000
            try:
                text = await asr_mod.transcribe_slice(
                    client,
                    base_url=settings.siliconflow_base_url,
                    api_key=settings.siliconflow_api_key,
                    model=settings.asr_model,
                    audio_path=str(audio),
                )
                transcripts.append(TranscriptSegment(start_ms=start, end_ms=start + 60_000, text=text))
            except Exception as exc:  # 单片失败容忍
                failures.append(exc)
                logger.warning("ASR 切片失败(容忍): %s", exc)

    if not transcripts and failures:
        # 全部失败不抛出:按"单路失败容忍"降级为 BranchResult.error(带 cause),
        # 由上层判断双路皆败才整体失败;否则无 ASR Key/ASR 故障时 OCR-only 也无法工作
        cause = failures[0]
        return BranchResult(
            error=PipelineError(
                f"ASR 全部 {len(slices)} 个切片失败(首因: {cause})",
                cause=cause,
                suppressed=failures[1] if len(failures) > 1 else None,
            )
        )
    if failures:
        logger.warning("ASR 部分失败: %d/%d 片", len(failures), len(slices))
    return BranchResult(items=transcripts)


async def _frames_branch(
    video_path: str, work_dir: Path, settings: Settings, http: "object | None"
) -> BranchResult:
    try:
        work_dir.mkdir(parents=True, exist_ok=True)
        pattern = str(work_dir / "frame_%06d.jpg")
        log = await _run_subprocess(
            frames_mod.build_scene_detect_cmd(str(video_path), pattern, settings.ffmpeg_bin),
            timeout_s=900,
        )
        frame_files = sorted(work_dir.glob("frame_*.jpg"))
        if not frame_files:
            return BranchResult(error=RuntimeError("场景检测未产出关键帧"))
        timestamps = frames_mod.frame_timestamps(log, len(frame_files))
    except Exception as exc:
        return BranchResult(error=exc)

    items: list[FrameItem] = []
    failures: list[Exception] = []
    previous_hash: int | None = None
    for frame_file, ts in zip(frame_files, timestamps):
        try:
            current_hash = frames_mod.image_dhash(str(frame_file))
            if frames_mod.is_duplicate_frame(previous_hash, current_hash):
                previous_hash = current_hash
                continue
            previous_hash = current_hash
        except Exception as exc:  # dHash 不可用(Pillow 缺失等)→ 不去重继续
            logger.warning("dHash 跳过: %s", exc)
        ocr_text = await _run_ocr(str(frame_file), settings.ocr_command)
        # 无对象存储:帧引用降级为本地路径 + 时间戳锚点(前端仍可定位)
        items.append(
            FrameItem(timestamp_ms=ts, url=f"{frame_file}#timestampMs={ts}", ocr_text=ocr_text)
        )
    if failures and not items:
        return BranchResult(error=failures[0])
    return BranchResult(items=items)


async def _run_ocr(image_path: str, ocr_bin: str) -> str:
    try:
        proc = await asyncio.create_subprocess_exec(
            ocr_bin, image_path, "stdout", "-l", "chi_sim+eng",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=120)
        return stdout.decode("utf-8", errors="replace").strip()
    except Exception as exc:
        logger.warning("OCR 单帧失败(容忍): %s", exc)
        return ""


async def build_video_context(
    video_path: str,
    user_goal: str,
    settings: Settings,
    *,
    work_dir: str | None = None,
    asr_branch=None,
    frames_branch=None,
) -> VideoContext:
    """双分支并行构建;单路失败容忍,双路皆败抛 PipelineError。

    asr_branch / frames_branch 可注入(测试与无 ffmpeg 环境降级)。
    """
    work = Path(work_dir or f"{video_path}.work")
    if asr_branch is None:
        asr_branch = _asr_branch(video_path, work / "asr", settings, None)
    if frames_branch is None:
        frames_branch = _frames_branch(video_path, work / "frames", settings, None)

    asr_res, frame_res = await asyncio.gather(asr_branch, frames_branch)

    if asr_res.error is not None and frame_res.error is not None:
        raise PipelineError(
            "ASR 与关键帧双路皆败",
            cause=asr_res.error,
            suppressed=frame_res.error,
        )
    if asr_res.error is not None:
        logger.warning("ASR 分支失败,仅保留视觉信息: %s", asr_res.error)
    if frame_res.error is not None:
        logger.warning("关键帧分支失败,仅保留语音信息: %s", frame_res.error)

    segments = merge_windows(asr_res.items, frame_res.items, settings.window_ms)
    return VideoContext(source=str(video_path), user_goal=user_goal.strip(), segments=segments)
