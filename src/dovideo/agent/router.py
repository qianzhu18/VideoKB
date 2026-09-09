"""模式路由(移植自 service/ModeRouter.java):永不失败,失败/超时/非法一律回退 GENERAL。"""

from __future__ import annotations

from dovideo.config import Settings
from dovideo.core.analysis import AnalysisMode
from dovideo.core.llm import (
    LLMClient,
    PermanentLLMError,
    RetriableLLMError,
    StructuredParseError,
    structured_chat,
)
from dovideo.core.prompts import classify_mode_messages
from dovideo.core.telemetry import Telemetry
from dovideo.core.textnorm import clip

FALLBACK_REASON = "自动路由当前繁忙,已回退通用模式"


async def route_mode(
    llm: LLMClient,
    goal: str,
    *,
    settings: Settings,
    telemetry: Telemetry,
    budget,
) -> tuple[AnalysisMode, str]:
    """返回 (mode, reason);该函数从不抛业务异常(预算耗尽除外——那是全局闸门)。"""
    if not goal.strip():
        telemetry.incr("modeRouterFallbacks")
        return AnalysisMode.GENERAL, "目标为空,使用通用模式"
    try:
        payload = await structured_chat(
            llm,
            classify_mode_messages(goal),
            timeout_s=15.0,
            budget=budget,
            telemetry=telemetry,
        )
        mode = AnalysisMode.from_nullable(str(payload.get("mode", "")))
        reason = clip(str(payload.get("reason", "")).strip(), 40, "") or "已按意图路由"
        return mode, reason
    except (RetriableLLMError, PermanentLLMError, StructuredParseError):
        telemetry.incr("modeRouterFallbacks")
        return AnalysisMode.GENERAL, FALLBACK_REASON
