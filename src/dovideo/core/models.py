"""视频上下文数据结构(移植自 dto/VideoContext.java / dto/TranscriptSegment.java)。"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, model_validator


class TranscriptSegment(BaseModel):
    """ASR 分片转写(原版按 60s 音频切片,时间轴即切片区间)。"""

    model_config = ConfigDict(populate_by_name=True)

    start_ms: int
    end_ms: int
    text: str = ""


class FrameItem(BaseModel):
    """OCR 关键帧:时间戳 + 帧引用 + 识别文本。"""

    timestamp_ms: int
    url: str = ""
    ocr_text: str = ""


class VideoSegment(BaseModel):
    """统一时序段:60 秒窗口内融合的语音 + 画面文字 + 证据帧。"""

    model_config = ConfigDict(populate_by_name=True)

    start_ms: int
    end_ms: int
    transcript: str = ""
    ocr_texts: list[str] = Field(default_factory=list)
    evidence_frames: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def _check(self) -> "VideoSegment":
        if self.start_ms < 0:
            raise ValueError("start_ms must be >= 0")
        if self.end_ms <= self.start_ms:
            raise ValueError("end_ms must be greater than start_ms")
        return self

    def has_content(self) -> bool:
        return bool(self.transcript or self.ocr_texts)


class VideoContext(BaseModel):
    """时序多模态统一上下文(原版构造器即校验;Checkpoint 保存时 source 置空可跨 media 复用)。"""

    model_config = ConfigDict(populate_by_name=True)

    source: str = ""
    user_goal: str = ""
    segments: list[VideoSegment] = Field(default_factory=list)

    def transcript_text(self) -> str:
        return "\n".join(s.transcript for s in self.segments if s.transcript)

    def span_ms(self) -> int:
        if not self.segments:
            return 0
        return max(s.end_ms for s in self.segments) - min(s.start_ms for s in self.segments)


class VideoChunk(BaseModel):
    """5 分钟检索分块:LLM 摘要 + 关键词 + 向量 + 回指原始 segment 索引。"""

    model_config = ConfigDict(populate_by_name=True)

    start_ms: int
    end_ms: int
    segment_summary: str = ""
    keywords: list[str] = Field(default_factory=list)
    embedding: list[float] = Field(default_factory=list)
    segment_indexes: list[int] = Field(default_factory=list)

    @property
    def key(self) -> str:
        return f"{self.start_ms}:{self.end_ms}"
