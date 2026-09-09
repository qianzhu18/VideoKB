"""FastAPI 服务层:复刻原项目 REST/SSE 契约(统一信封 {code, message, data};异步受理 202)。

无 API Key 时自动切换离线 FakeLLM,整个核心链路可本地演示。
注意:本模块不能加 `from __future__ import annotations`,否则 FastAPI 无法内省 body 模型。
"""

import asyncio
import hashlib
import json
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel

from dovideo.agent.loop import LoopOutcome
from dovideo.agent.router import route_mode
from dovideo.checkpoints.store import CheckpointStore, goal_digest
from dovideo.config import Settings
from dovideo.core.analysis import AnalysisMode, TaskStage
from dovideo.core.budget import BudgetExhaustedError
from dovideo.core.fake import FakeLLM
from dovideo.core.prompts import follow_up_messages, render_segments
from dovideo.core.telemetry import Telemetry
from dovideo.jobs.worker import Deps, DuplicateTaskError, InlineJobRunner, PermanentAnalysisError


def envelope(code: int = 0, message: str = "success", data=None) -> dict:
    return {"code": code, "message": message, "data": data}


class EventBus:
    """进程内事件总线(SSE);跨实例可替换为 Redis pub/sub(契约不变)。"""

    def __init__(self) -> None:
        self._subscribers: dict[str, list[asyncio.Queue]] = {}

    def subscribe(self, key: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue()
        self._subscribers.setdefault(key, []).append(queue)
        return queue

    def unsubscribe(self, key: str, queue: asyncio.Queue) -> None:
        queues = self._subscribers.get(key, [])
        if queue in queues:
            queues.remove(queue)

    def publish(self, key: str, event: dict) -> None:
        for queue in list(self._subscribers.get(key, [])):
            try:
                queue.put_nowait(event)
            except Exception:
                pass


@dataclass(slots=True)
class AppState:
    deps: Deps
    runner: InlineJobRunner
    bus: EventBus
    media_registry: dict = field(default_factory=dict)  # media_id -> {path, contentHash, context}


def build_default_deps(settings: Settings | None = None) -> Deps:
    from dovideo.context.pipeline import build_video_context

    settings = settings or Settings.from_env()
    store = CheckpointStore(settings.db_path)
    llm = (
        _real_llm(settings)
        if settings.llm_configured
        else FakeLLM(
            plans=[{"understoodGoal": "离线演示目标", "tasks": ["概述视频内容"]}],
            executes=[{
                "title": "离线演示",
                "conclusions": ["这是一段离线演示内容"],
                "evidence": [{"timestampMs": 0, "source": "ASR", "content": "离线", "claim": "这是一段离线演示内容"}],
                "suggestions": ["配置 SILICONFLOW_API_KEY 后启用真实分析"],
            }],
            critiques=[{"passed": True, "feedback": []}],
        )
    )
    return Deps(settings=settings, llm=llm, store=store, context_builder=_default_context_builder)


def _real_llm(settings: Settings):
    from dovideo.core.llm import SiliconFlowLLMClient, heuristic_cost_fn

    return SiliconFlowLLMClient(
        base_url=settings.siliconflow_base_url,
        api_key=settings.siliconflow_api_key,
        model=settings.llm_model,
        embedding_model=settings.embedding_model,
    )


async def _default_context_builder(media_id: str, goal: str):
    """默认上下文构建器:媒体注册后会被替换为真实流水线;未注册时报错。"""
    raise ValueError(f"media {media_id} 未注册本地视频文件")


def _register_media_builder(state: AppState, media_id: str, path: str) -> None:
    """把本地文件挂进上下文构建器(真实 ffmpeg/ASR/OCR 流水线)。"""
    state.media_registry[media_id] = {"path": path, "contentHash": ""}

    async def builder(mid: str, goal: str):
        info = state.media_registry.get(mid)
        if not info:
            raise ValueError(f"media {mid} 未注册")
        from dovideo.context.pipeline import build_video_context as _bvc

        return await _bvc(info["path"], goal, state.deps.settings)

    state.deps.context_builder = builder


def create_app(state: AppState | None = None, settings: Settings | None = None) -> FastAPI:
    if state is None:
        deps = build_default_deps(settings)
        bus = EventBus()
        deps.broadcast = lambda media_id, digest, stage, message: bus.publish(
            f"{media_id}:{digest}", {"state": _state_of(stage), "stage": stage, "message": message}
        )
        state = AppState(deps=deps, runner=InlineJobRunner(deps, sleep_fn=_noop_sleep), bus=bus)
    app = FastAPI(title="dovideo", version="0.1.0")
    app.state.dovideo = state
    runtime_settings = state.deps.settings

    @app.middleware("http")
    async def token_guard(request: Request, call_next):
        """可选 Bearer 认证:设置 DOVIDEO_API_TOKEN 后,除 /health 外全部要求令牌。"""
        token = runtime_settings.api_token
        if token and request.url.path != "/health":
            auth = request.headers.get("authorization", "")
            if auth != f"Bearer {token}":
                return JSONResponse(status_code=401, content=envelope(401, "未授权"))
        return await call_next(request)

    # ── 媒体:multipart 直传(平台形态)与本地文件注册(开发形态)───────
    ALLOWED_SUFFIXES = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".mp3", ".m4a", ".wav"}

    @app.post("/media/upload")
    async def upload_media(file: UploadFile = File(...)):
        suffix = Path(file.filename or "").suffix.lower()
        if suffix not in ALLOWED_SUFFIXES:
            raise HTTPException(status_code=400, detail=f"不支持的媒体类型: {suffix}")
        media_id = uuid.uuid4().hex[:16]
        dest_dir = Path(runtime_settings.media_dir)
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / f"{media_id}{suffix}"
        hasher = hashlib.md5()
        size = 0
        with open(dest, "wb") as out:
            while chunk := await file.read(1 << 20):
                out.write(chunk)
                hasher.update(chunk)
                size += len(chunk)
        if size == 0:
            dest.unlink(missing_ok=True)
            raise HTTPException(status_code=400, detail="空文件")
        _register_media_builder(state, media_id, str(dest))
        state.media_registry[media_id]["contentHash"] = hasher.hexdigest()
        return envelope(data={"mediaId": media_id, "bytes": size, "md5": hasher.hexdigest()})

    class RegisterMediaBody(BaseModel):
        media_id: str
        path: str
        content_hash: str = ""

    @app.post("/media/register")
    async def register_media(body: RegisterMediaBody):
        if not Path(body.path).exists():
            raise HTTPException(status_code=400, detail=f"文件不存在: {body.path}")
        _register_media_builder(state, body.media_id, body.path)
        state.media_registry[body.media_id]["contentHash"] = body.content_hash
        return envelope(data={"mediaId": body.media_id})

    # ── 模式路由(永不失败)────────────────────────────────────────
    class RouteBody(BaseModel):
        goal: str

    @app.post("/analysis/route")
    async def route(body: RouteBody):
        telemetry = Telemetry()
        from dovideo.core.budget import AgentExecutionBudget

        budget = AgentExecutionBudget(max_duration_ms=15_000, max_tokens=5_000)
        mode, reason = await route_mode(
            state.deps.llm, body.goal, settings=state.deps.settings, telemetry=telemetry, budget=budget
        )
        return envelope(data={"mode": mode.value, "reason": reason})

    # ── 提交异步分析 ──────────────────────────────────────────────
    class AnalyzeBody(BaseModel):
        media_id: str
        goal: str
        mode: str = "GENERAL"

    @app.post("/analysis/ai")
    async def analyze(body: AnalyzeBody):
        try:
            mode = AnalysisMode.from_request(body.mode)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        digest = goal_digest(body.goal, mode.value)
        result_key = f"goal:{digest}:result"
        stage, payload = state.deps.store.load(body.media_id, result_key)
        if payload is not None:
            return envelope(data={"mediaId": body.media_id, "reused": True, "stage": stage})

        if state.deps.store.has_active(body.media_id, digest):
            raise HTTPException(status_code=409, detail="任务已在执行")
        # 幂等键由 runner 内部 claim(与原版 dispatch/消费两段语义一致)

        async def _job():
            try:
                await state.runner.submit(body.media_id, body.goal, mode)
            except (PermanentAnalysisError, BudgetExhaustedError):
                pass  # 终态已落台账/阶段

        asyncio.get_running_loop().create_task(_job())
        from fastapi.responses import JSONResponse

        return JSONResponse(
            status_code=202,
            content=envelope(data={"mediaId": body.media_id, "goalDigest": digest, "accepted": True}),
        )

    # ── 状态 / SSE ────────────────────────────────────────────────
    def _derive_state(media_id: str, digest: str) -> dict:
        stage = state.deps.store.load_stage(media_id, digest)
        _, result = state.deps.store.load(media_id, f"goal:{digest}:result")
        if result is not None:
            state_name = "COMPLETED"
        elif state.deps.store.has_active(media_id, digest) or stage:
            state_name = "PROCESSING" if stage and stage not in ("QUEUED",) else "QUEUED"
        else:
            state_name = "NOT_STARTED"
        return {"state": state_name, "stage": stage or "NOT_STARTED", "message": "", "result": result}

    @app.get("/analysis/analysis-status")
    async def analysis_status(mediaId: str, goal: str, mode: str = "GENERAL"):
        digest = goal_digest(goal, AnalysisMode.from_nullable(mode).value)
        return envelope(data=_derive_state(mediaId, digest))

    @app.get("/analysis/analysis-events")
    async def analysis_events(mediaId: str, goal: str, mode: str = "GENERAL"):
        digest = goal_digest(goal, AnalysisMode.from_nullable(mode).value)
        key = f"{mediaId}:{digest}"

        async def stream():
            queue = state.bus.subscribe(key)
            try:
                current = _derive_state(mediaId, digest)
                yield sse_event(current)
                if current["state"] in ("COMPLETED", "FAILED"):
                    return
                while True:
                    try:
                        event = await asyncio.wait_for(queue.get(), timeout=1800)
                    except asyncio.TimeoutError:
                        return
                    merged = {**_derive_state(mediaId, digest), **event}
                    if merged.get("result") is not None:
                        merged["state"] = "COMPLETED"
                    yield sse_event(merged)
                    if merged.get("state") in ("COMPLETED", "FAILED", "BUDGET_EXHAUSTED"):
                        return
            finally:
                state.bus.unsubscribe(key, queue)

        return StreamingResponse(stream(), media_type="text/event-stream")

    def sse_event(data: dict) -> str:
        return f"event: task-status\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

    # ── 计划 / 轨迹 / 证据检索 / 追问 ─────────────────────────────
    @app.get("/analysis/agent-plan")
    async def agent_plan(mediaId: str, goal: str, mode: str = "GENERAL"):
        digest = goal_digest(goal, AnalysisMode.from_nullable(mode).value)
        _, plan = state.deps.store.load(mediaId, f"goal:{digest}:plan")
        return envelope(data=plan)

    @app.get("/analysis/agent-trace")
    async def agent_trace(mediaId: str, goal: str, mode: str = "GENERAL"):
        digest = goal_digest(goal, AnalysisMode.from_nullable(mode).value)
        return envelope(data=state.deps.traces.get(f"{mediaId}:{digest}"))

    @app.get("/analysis/evidence-search")
    async def evidence_search(mediaId: str, q: str, goal: str = "", mode: str = "GENERAL"):
        context = await _load_context(state, mediaId)
        if context is None:
            raise HTTPException(status_code=404, detail="media 上下文不存在")
        digest = goal_digest(goal, AnalysisMode.from_nullable(mode).value)
        chunks_payload = state.deps.store.load(mediaId, "media:chunks")[1] or []
        from dovideo.core.models import VideoChunk

        chunks = [VideoChunk.model_validate(c) for c in chunks_payload]
        from dovideo.retrieval.search import evidence_search as _search
        from dovideo.core.budget import AgentExecutionBudget

        telemetry = Telemetry()
        budget = AgentExecutionBudget(max_duration_ms=30_000, max_tokens=20_000)
        hits = await _search(
            context, chunks, q,
            llm=state.deps.llm, settings=state.deps.settings,
            telemetry=telemetry, budget=budget, store=state.deps.vector_store, media_id=mediaId,
        )
        return envelope(data=hits)

    class FollowUpBody(BaseModel):
        media_id: str
        question: str
        goal: str = ""
        mode: str = "GENERAL"

    @app.post("/analysis/follow-up")
    async def follow_up(body: FollowUpBody):
        context = await _load_context(state, body.media_id)
        if context is None:
            raise HTTPException(status_code=404, detail="media 上下文不存在")
        digest = goal_digest(body.goal, AnalysisMode.from_nullable(body.mode).value)
        chunks_payload = state.deps.store.load(body.media_id, "media:chunks")[1] or []
        from dovideo.core.models import VideoChunk

        chunks = [VideoChunk.model_validate(c) for c in chunks_payload]
        from dovideo.retrieval.search import select_relevant as _select
        from dovideo.core.budget import AgentExecutionBudget

        telemetry = Telemetry()
        budget = AgentExecutionBudget(max_duration_ms=60_000, max_tokens=30_000)
        selected = await _select(
            context, chunks, body.question,
            llm=state.deps.llm, settings=state.deps.settings,
            telemetry=telemetry, budget=budget, store=state.deps.vector_store,
            media_id=body.media_id,
        )
        _, result_payload = state.deps.store.load(body.media_id, f"goal:{digest}:result")
        previous_summary = ""
        if result_payload:
            previous_summary = result_payload.get("title", "") + "\n" + "\n".join(
                result_payload.get("conclusions", [])
            )
        from dovideo.core.llm import structured_chat

        answer = await structured_chat(
            state.deps.llm,
            follow_up_messages(
                render_segments(selected, state.deps.settings.max_context_chars),
                body.goal or context.user_goal,
                previous_summary,
                body.question,
            ),
            timeout_s=state.deps.settings.llm_timeout_seconds,
            budget=budget,
            telemetry=telemetry,
        )
        return envelope(data={"answer": str(answer.get("answer", answer.get("text", ""))) or _plain(answer)})

    @app.get("/health")
    async def health():
        return envelope(data="UP")

    return app


def _plain(payload: dict) -> str:
    return payload.get("markdown") or payload.get("content") or json.dumps(payload, ensure_ascii=False)


async def _load_context(state: AppState, media_id: str):
    info = state.media_registry.get(media_id)
    if info and info.get("context") is not None:
        return info["context"]
    _, payload = state.deps.store.load(media_id, "media:context")
    if payload:
        from dovideo.core.models import VideoContext

        return VideoContext.model_validate(payload)
    return None


async def _noop_sleep(_: float) -> None:
    return None


def _state_of(stage: str) -> str:
    if stage in ("COMPLETED", "ANALYSIS_COMPLETED", "ANALYSIS_COMPLETED_WITH_WARNINGS", "COMPLETED_REUSED"):
        return "COMPLETED"
    if stage in ("FAILED", "DEAD_LETTERED", "BUDGET_EXHAUSTED"):
        return "FAILED"
    if stage == "QUEUED":
        return "QUEUED"
    return "PROCESSING"


__all__ = ["create_app", "build_default_deps", "AppState", "EventBus", "envelope", "LoopOutcome"]
