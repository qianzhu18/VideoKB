"""确定性证据核验(移植自 service/EvidenceVerificationService.java)。

LLM Critic 只做语义层判断;时间戳证据由纯代码核验:
- supported(): 证据时间戳落在某 segment 区间、source 兼容、且归一化 content 是
  该 segment 对应源文本(ASR→transcript,OCR→ocr_texts 拼接)的子串
- claim 匹配: 归一化后 claim == 某条结论(Executor prompt 要求逐字复制)
- 覆盖率: 每条 conclusion 至少绑定一条有效证据
"""

from __future__ import annotations

from dataclasses import dataclass, field

from dovideo.core.analysis import AnalysisResult, Evidence
from dovideo.core.models import VideoSegment
from dovideo.core.textnorm import clip, normalize


def source_text_for(segment: VideoSegment, source: str) -> str:
    parts: list[str] = []
    if "ASR" in source:
        parts.append(segment.transcript)
    if "OCR" in source:
        parts.append(" ".join(segment.ocr_texts))
    return normalize(" ".join(parts))


def source_supported(source: str) -> bool:
    return "ASR" in source or "OCR" in source


@dataclass(slots=True)
class VerifyReport:
    unsupported_evidence: list[str] = field(default_factory=list)
    claim_mismatches: list[str] = field(default_factory=list)
    uncovered_conclusions: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not (
            self.unsupported_evidence or self.claim_mismatches or self.uncovered_conclusions
        )

    def messages(self) -> list[str]:
        return self.unsupported_evidence + self.claim_mismatches + self.uncovered_conclusions


def _segment_for_timestamp(segments: list[VideoSegment], timestamp_ms: int) -> VideoSegment | None:
    for seg in segments:
        if seg.start_ms <= timestamp_ms < seg.end_ms:
            return seg
    return None


def evidence_supported(ev: Evidence, segments: list[VideoSegment]) -> bool:
    if not source_supported(ev.source):
        return False
    seg = _segment_for_timestamp(segments, ev.timestamp_ms)
    if seg is None:
        return False
    content = normalize(ev.content)
    if not content:
        return False
    return content in source_text_for(seg, ev.source)


def verify_result(result: AnalysisResult, segments: list[VideoSegment]) -> VerifyReport:
    report = VerifyReport()
    normalized_conclusions = {normalize(c): c for c in result.conclusions if normalize(c)}

    supported_claim_keys: set[str] = set()
    for ev in result.evidence:
        if not evidence_supported(ev, segments):
            report.unsupported_evidence.append(
                f"证据无法在原始 ASR/OCR 中核验: {ev.timestamp_ms} ({clip(ev.content, 40)})"
            )
            continue
        claim_key = normalize(ev.claim)
        if claim_key not in normalized_conclusions:
            report.claim_mismatches.append(
                f"证据 claim 与结论不匹配: {clip(ev.claim, 50)}"
            )
        else:
            supported_claim_keys.add(claim_key)

    for key, original in normalized_conclusions.items():
        if key not in supported_claim_keys:
            report.uncovered_conclusions.append(f"结论缺少有效证据支撑: {clip(original, 50)}")

    return report
