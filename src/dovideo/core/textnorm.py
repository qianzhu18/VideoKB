"""文本归一化与启发式估算(移植自原项目 AgentTelemetry / EvidenceVerificationService)。"""

from __future__ import annotations

import math
import unicodedata

# P=标点 S=符号 Z=分隔符(含空格) —— 与原版"小写+删除全部标点/符号/空白"等价,且覆盖中文标点
_STRIP_CATEGORIES = ("P", "S", "Z")


def normalize(text: str) -> str:
    """小写化并删除全部标点/符号/空白,仅保留字母数字与 CJK 等表意字符。"""
    return "".join(
        ch
        for ch in text.lower()
        if not unicodedata.category(ch).startswith(_STRIP_CATEGORIES) and not ch.isspace()
    )


def estimate_tokens(text: str) -> int:
    """Token 启发式:非 ASCII 码点每字符 1 token,ASCII 每 4 字符 1 token(向上取整)。"""
    if not text:
        return 0
    ascii_count = sum(1 for ch in text if ord(ch) < 128)
    other_count = len(text) - ascii_count
    return other_count + math.ceil(ascii_count / 4) if ascii_count else other_count


def estimate_messages_tokens(messages: list[dict[str, str]]) -> int:
    return sum(estimate_tokens(m.get("content", "")) for m in messages)


def clip(text: str, limit: int, suffix: str = "…") -> str:
    if len(text) <= limit:
        return text
    return text[: max(0, limit - len(suffix))] + suffix if limit > 0 else ""


def term_score(terms: list[str], *texts: str) -> float:
    """归一化子串包含命中率 = matched / terms(原版检索打分的 termScore)。"""
    if not terms:
        return 0.0
    haystack = normalize(" ".join(texts))
    if not haystack:
        return 0.0
    matched = sum(1 for t in terms if t and normalize(t) in haystack)
    return matched / len(terms)


def format_ts(ms: int) -> str:
    """毫秒 → [mm:ss] 展示。"""
    total = max(0, ms) // 1000
    return f"{total // 60:02d}:{total % 60:02d}"
