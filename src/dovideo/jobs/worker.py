"""分析任务编排(移植自 consumer/VideoAnalysisConsumer.java + AnalysisDispatchService.java)。

- 提交幂等:claim_active(mediaId, digest) SETNX 语义
- 异常类型即重试语义:Retriable* → 上层重投;Permanent* → 失败台账 + 死信;预算耗尽 → 终态不重试
- 内容级复用:media:context / media:chunks 与内容指纹绑定,换目标不重烧 ASR/OCR
- 阶段事件经 EventBus 广播(SSE 消费)
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Awaitable, Callable

from dovideo.agent.loop import AgentLoop, LoopOutcome
from dovideo.checkpoints.store import CheckpointStore, goal_digest
from dovideo.config import Settings
from dovideo.context.asr import RetriableASRError
from dovideo.core.analysis import AnalysisMode, TaskStage
from dovideo.core.budget import BudgetExhaustedError
from dovideo.core.llm import LLMClient, PermanentLLMError, RetriableLLMError
from dovideo.core.models import VideoContext
from dovideo.core.telemetry import Telemetry
from dovideo.retrieval.chunking import build_chunks
from dovideo.retrieval.search import select_relevant
from dovideo.retrieval.store import CompositeVectorStore


class DuplicateTaskError(Exception):
    """同一 (content, goal) 任务已在执行(原版返回 409)。"""


ContextBuilder = Callable[[str, str], Awaitable[VideoContext]]
Broadcaster = Callable[[str, str, str, str], None]  # (media_id, digest, stage, message)


@dataclass(slots=True)
class Deps:
    settings: Settings
    llm: LLMClient
    store: CheckpointStore
    context_builder: ContextBuilder
    vector_store: CompositeVectorStore | None = None
    broadcast: Broadcaster | None = None
    cost_fn=None
    traces: dict = field(default_factory=dict)


async def run_analysis_job(
    deps: Deps,
    media_id: str,
    goal: str,
    mode: AnalysisMode = AnalysisMode.GENERAL,
    *,
    context: VideoContext | None = None,
) -> LoopOutcome:
    """单次尝试;可重试异常向上抛,由 runner 决定重投。"""
    settings = deps.settings
    digest = goal_digest(goal, mode.value)
    telemetry = Telemetry()

    def broadcast(stage: str, message: str = "") -> None:
        if deps.broadcast:
            deps.broadcast(media_id, digest, stage, message)

    def emit_stage(stage: TaskStage, message: str = "") -> None:
        telemetry.record_stage(stage.value, 0)
        broadcast(stage.value, message)
        deps.store.save_stage(media_id, digest, stage.value)

    if not deps.store.claim_active(media_id, digest):
        raise DuplicateTaskError(f"任务已在执行: {media_id}:{digest[:12]}")

    try:
        emit_stage(TaskStage.CONSUMING)

        # ── VideoContext(内容级复用)────────────────────────────────
        emit_stage(TaskStage.VIDEO_CONTEXT)
        _, ctx_payload = deps.store.load(media_id, "media:context")
        if context is None and ctx_payload:
            context = VideoContext.model_validate(ctx_payload)
        if context is None:
            context = await deps.context_builder(media_id, goal)
            # 保存时置空 source:上下文与目标/存储位置无关,可跨 media 复用
            saved = context.model_dump()
            saved["source"] = ""
            deps.store.save(media_id, "media:context", saved, stage="CONTEXT_COMPLETED")
        emit_stage(TaskStage.CONTEXT_COMPLETED, f"{len(context.segments)} 段")

        # ── 检索分块(内容级复用)────────────────────────────────────
        _, chunks_payload = deps.store.load(media_id, "media:chunks")
        chunks = []
        if chunks_payload:
            from dovideo.core.models import VideoChunk

            chunks = [VideoChunk.model_validate(c) for c in chunks_payload]
        if not chunks:
            from dovideo.core.budget import AgentExecutionBudget

            chunk_budget = AgentExecutionBudget(
                max_duration_ms=settings.agent_max_duration_ms,
                max_tokens=settings.agent_max_estimated_tokens,
            )
            chunks = await build_chunks(
                context, settings=settings, llm=deps.llm, telemetry=telemetry, budget=chunk_budget
            )
            deps.store.save(
                media_id, "media:chunks", [c.model_dump() for c in chunks], stage="CHUNKS_COMPLETED"
            )
            if deps.vector_store is not None:
                await deps.vector_store.sync(media_id, chunks)
        emit_stage(TaskStage.CHUNKS_COMPLETED, f"{len(chunks)} 块")

        # ── 混合检索选出相关上下文 ──────────────────────────────────
        emit_stage(TaskStage.RETRIEVAL)
        from dovideo.core.budget import AgentExecutionBudget

        retrieval_budget = AgentExecutionBudget(
            max_duration_ms=settings.agent_max_duration_ms,
            max_tokens=settings.agent_max_estimated_tokens,
        )
        selected = await select_relevant(
            context,
            chunks,
            goal,
            llm=deps.llm,
            settings=settings,
            telemetry=telemetry,
            budget=retrieval_budget,
            store=deps.vector_store,
            media_id=media_id,
        )

        # ── AgentLoop ──────────────────────────────────────────────
        emit_stage(TaskStage.AGENT_LOOP)
        loop = AgentLoop(
            llm=deps.llm,
            store=deps.store,
            settings=settings,
            telemetry=telemetry,
            events=lambda stage, message: broadcast(stage, message),
            cost_fn=deps.cost_fn,
        )
        outcome = await loop.run(
            media_id,
            context,
            goal,
            mode,
            selected=selected,
            chunks=chunks,
            store_handle=deps.vector_store,
            media_ref=media_id,
        )
        deps.store.release_active(media_id, digest)
        deps.traces[f"{media_id}:{digest}"] = telemetry.to_dict()
        broadcast(TaskStage.COMPLETED.value, "")
        return outcome

    except BudgetExhaustedError as exc:
        deps.store.save_stage(media_id, digest, TaskStage.BUDGET_EXHAUSTED.value)
        deps.store.record_failure(
            media_id, digest, mode.value, TaskStage.BUDGET_EXHAUSTED.value, "BudgetExhausted", str(exc)
        )
        broadcast(TaskStage.BUDGET_EXHAUSTED.value, str(exc))
        raise
    except (RetriableLLMError, RetriableASRError):
        # 可重试:保留 active 键由 runner 管理;阶段记 RETRYING
        deps.store.save_stage(media_id, digest, TaskStage.RETRYING.value)
        broadcast(TaskStage.RETRYING.value, "第三方调用抖动,准备重试")
        raise
    except (PermanentLLMError, ValueError) as exc:
        deps.store.save_stage(media_id, digest, TaskStage.FAILED.value)
        deps.store.record_failure(
            media_id, digest, mode.value, TaskStage.FAILED.value, type(exc).__name__, str(exc)
        )
        deps.store.release_active(media_id, digest)
        broadcast(TaskStage.FAILED.value, str(exc))
        raise PermanentAnalysisError(str(exc)) from exc
    except asyncio.CancelledError:
        deps.store.release_active(media_id, digest)
        raise


class PermanentAnalysisError(Exception):
    """永久失败:重投无益,进失败台账等待人工重放。"""


class InlineJobRunner:
    """最小任务执行器:指数退避重试(1/2/4s,可注入 sleep),最多 3 次投递。

    生产可替换为 ARQ worker(重投语义一致);测试注入 sleep=0。
    """

    def __init__(
        self,
        deps: Deps,
        *,
        max_deliveries: int = 3,
        sleep_fn=asyncio.sleep,
    ) -> None:
        self.deps = deps
        self.max_deliveries = max_deliveries
        self._sleep = sleep_fn

    async def submit(
        self, media_id: str, goal: str, mode: AnalysisMode = AnalysisMode.GENERAL, **kwargs
    ) -> LoopOutcome:
        attempt = 0
        while True:
            attempt += 1
            try:
                return await run_analysis_job(self.deps, media_id, goal, mode, **kwargs)
            except DuplicateTaskError:
                raise
            except BudgetExhaustedError:
                raise  # 终态,不重试
            except PermanentAnalysisError:
                raise
            except (RetriableLLMError, RetriableASRError) as exc:
                if attempt >= self.max_deliveries:
                    digest = goal_digest(goal, mode.value)
                    self.deps.store.record_failure(
                        media_id, digest, mode.value, TaskStage.DEAD_LETTERED.value,
                        type(exc).__name__, str(exc),
                    )
                    self.deps.store.release_active(media_id, digest)
                    raise PermanentAnalysisError(f"重试 {attempt} 次后仍失败: {exc}") from exc
                # 释放幂等键后重投(生产由 MQ 重投;本地循环等价)
                self.deps.store.release_active(media_id, goal_digest(goal, mode.value))
                await self._sleep(2 ** (attempt - 1))
