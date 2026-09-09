"""MCP Server(扩展,原项目没有):把视频分析能力暴露给 Claude Code 等客户端。

需要 `pip install "mcp>=1.2"`;未安装时 import 本模块给出可读错误。
"""

from __future__ import annotations


def build_server(deps=None):
    try:
        from mcp.server.fastmcp import FastMCP
    except ImportError as exc:  # pragma: no cover
        raise RuntimeError('MCP 未安装:请执行 pip install "mcp>=1.2"') from exc

    import asyncio

    from dovideo.api.app import build_default_deps
    from dovideo.core.analysis import AnalysisMode

    deps = deps or build_default_deps()
    mcp = FastMCP("dovideo")

    @mcp.tool()
    async def analyze_video(media_id: str, goal: str, mode: str = "GENERAL") -> str:
        """对已注册的视频执行证据约束的 AgentLoop 分析,返回结构化结论与时间戳证据。"""
        from dovideo.jobs.worker import InlineJobRunner

        runner = InlineJobRunner(deps, sleep_fn=_zero_sleep)
        outcome = await runner.submit(media_id, goal, AnalysisMode.from_nullable(mode))
        return outcome.result.model_dump_json(by_alias=True, indent=2)

    @mcp.tool()
    async def evidence_search(media_id: str, query: str) -> str:
        """在视频上下文中做混合检索,返回带时间戳的证据片段(≤8 条)。"""
        import json

        from dovideo.api.app import _load_context
        from dovideo.core.budget import AgentExecutionBudget
        from dovideo.core.telemetry import Telemetry
        from dovideo.core.models import VideoChunk
        from dovideo.retrieval.search import evidence_search

        from dovideo.api.app import AppState, InlineJobRunner  # noqa: F401

        _, ctx_payload = deps.store.load(media_id, "media:context")
        if not ctx_payload:
            return "media 上下文不存在,请先执行 analyze_video"
        context = VideoContext.model_validate(ctx_payload)
        _, chunks_payload = deps.store.load(media_id, "media:chunks")
        chunks = [VideoChunk.model_validate(c) for c in (chunks_payload or [])]
        hits = await evidence_search(
            context, chunks, query,
            llm=deps.llm, settings=deps.settings,
            telemetry=Telemetry(),
            budget=AgentExecutionBudget(max_duration_ms=30_000, max_tokens=20_000),
            store=deps.vector_store, media_id=media_id,
        )
        return json.dumps(hits, ensure_ascii=False, indent=2)

    @mcp.tool()
    async def follow_up(media_id: str, question: str) -> str:
        """基于同一视频的既有分析继续追问,返回带时间戳引用的 Markdown 回答。"""
        from dovideo.api.app import create_app  # noqa: F401
        from dovideo.core.budget import AgentExecutionBudget
        from dovideo.core.llm import structured_chat
        from dovideo.core.prompts import follow_up_messages, render_segments
        from dovideo.core.telemetry import Telemetry
        from dovideo.retrieval.search import select_relevant
        from dovideo.core.models import VideoChunk, VideoContext

        _, ctx_payload = deps.store.load(media_id, "media:context")
        if not ctx_payload:
            return "media 上下文不存在,请先执行 analyze_video"
        context = VideoContext.model_validate(ctx_payload)
        _, chunks_payload = deps.store.load(media_id, "media:chunks")
        chunks = [VideoChunk.model_validate(c) for c in (chunks_payload or [])]
        selected = await select_relevant(
            context, chunks, question,
            llm=deps.llm, settings=deps.settings, telemetry=Telemetry(),
            budget=AgentExecutionBudget(max_duration_ms=60_000, max_tokens=30_000),
            store=deps.vector_store, media_id=media_id,
        )
        payload = await structured_chat(
            deps.llm,
            follow_up_messages(
                render_segments(selected, deps.settings.max_context_chars),
                context.user_goal, "", question,
            ),
            timeout_s=deps.settings.llm_timeout_seconds,
            budget=AgentExecutionBudget(max_duration_ms=60_000, max_tokens=30_000),
            telemetry=Telemetry(),
        )
        return str(payload.get("markdown") or payload.get("answer") or payload)

    return mcp


async def _zero_sleep(_: float) -> None:
    return None


if __name__ == "__main__":  # pragma: no cover
    build_server().run()
