"""ASR 分支(移植自 service/SegmentedTranscriptionService.java + utils/AliyunAsrUtils.java)。

- FFmpeg 按 60s 切片(-segment_time 60 -reset_timestamps 1)
- 逐片调硅基流动 TeleSpeechASR;异常类型即重试语义:
  429/5xx/网络 → RetriableASRError(可重试);其它 4xx → PermanentASRError(重投无益)
- 单片失败计数容忍;全部失败必须抛且携带 cause(否则上层把参数错误当抖动反复重投)
"""

from __future__ import annotations

import asyncio
import pathlib

import httpx


class RetriableASRError(Exception):
    pass


class PermanentASRError(Exception):
    pass


def audio_slice_cmd(
    video_path: str, out_dir: str, ffmpeg_bin: str = "ffmpeg", segment_seconds: int = 60
) -> list[str]:
    pattern = str(pathlib.Path(out_dir) / "audio_%03d.mp3")
    return [
        ffmpeg_bin,
        "-y",
        "-i",
        video_path,
        "-vn",
        "-acodec",
        "libmp3lame",
        "-f",
        "segment",
        "-segment_time",
        str(segment_seconds),
        "-reset_timestamps",
        "1",
        pattern,
    ]


async def transcribe_slice(
    client: httpx.AsyncClient,
    *,
    base_url: str,
    api_key: str,
    model: str,
    audio_path: str,
    max_attempts: int = 3,
    timeout_s: float = 120.0,
) -> str:
    """转写单个音频切片;3 次尝试、1/2/4s 指数退避。"""
    last_error: Exception | None = None
    for attempt in range(max_attempts):
        try:
            files = {"file": (pathlib.Path(audio_path).name, open(audio_path, "rb"), "audio/mpeg")}
            try:
                resp = await client.post(
                    f"{base_url.rstrip('/')}/audio/transcriptions",
                    headers={"Authorization": f"Bearer {api_key}"},
                    data={"model": model},
                    files=files,
                    timeout=timeout_s,
                )
            finally:
                files["file"][1].close()
        except (httpx.TransportError, asyncio.TimeoutError) as exc:
            last_error = RetriableASRError(f"网络错误: {exc}")
        else:
            if resp.status_code in (429,) or resp.status_code >= 500:
                last_error = RetriableASRError(f"HTTP {resp.status_code}: {resp.text[:200]}")
            elif resp.status_code >= 400:
                raise PermanentASRError(f"HTTP {resp.status_code}: {resp.text[:200]}")
            else:
                data = resp.json()
                text = data.get("text") or ""
                if not text.strip():
                    last_error = RetriableASRError("ASR 返回空文本")
                else:
                    return text
        if attempt < max_attempts - 1:
            await asyncio.sleep(2**attempt)
    assert last_error is not None
    raise last_error
