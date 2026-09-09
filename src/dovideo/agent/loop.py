"""证据约束的 AgentLoop(移植自 service/AgentLoopService.java,518 行的核心语义)。

闭环: Planner → (Executor → 草稿落盘 → Critic → 确定性护栏)×maxRounds
护栏链: normalize → 结构钳制 → 纯代码证据核验 → 一致性钳制
Critic 不通过 → 定向补证据(requiredTimestamps 钩子)→ 增量 replan → 下一轮
到轮次仍不通过 → ANALYSIS_COMPLETED_WITH_WARNINGS(保留产物,不丢弃)
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Callable

from dovideo.checkpoints.store import CheckpointStore, goal_digest
from dovideo.config import Settings
from dovideo.core.analysis import (
    AgentPlan,
    AgentState,
    AnalysisMode,
    AnalysisResult,
    CriticResult,
    Evidence,
    ModeProfile,
    ResultSection,
    get_profile,
    validate_plan,
)
from dovideo.core.budget import AgentExecutionBudget
from dovideo.core.evidence import VerifyReport, verify_result
from dovideo.core.llm import (
    LLMClient,
    PermanentLLMError,
    RetriableLLMError,
    StructuredParseError,
    structured_chat,
)
from dovideo.core.models import VideoContext, VideoSegment
from dovideo.core.prompts import (
    critique_messages,
    execute_messages,
    plan_messages,
    plan_repair_messages,
    render_segments,
)
from dovideo.core.telemetry import Telemetry
from dovideo.retrieval.search import context_for_retry, requires_evidence_refresh

StageEvent = Callable[[str, str], None]

DEFAULT_FEEDBACK = "重新检查目标覆盖、结构完整性和证据绑定"


@dataclass(slots=True)
class LoopOutcome:
    result: AnalysisResult
    passed: bool
    rounds: int
    warnings: list[str] = field(default_factory=list)
    stage_history: list[str] = field(default_factory=list)
    telemetry: dict | None = None


# ── 确定性护栏(纯函数,可单测)─────────────────────────────────────────
def normalize_critique(raw: dict | None) -> CriticResult:
    """null → 默认失败 + 固定 feedback;所有 list null → 空(防死循环/防假通过)。"""
    if raw is None:
        return CriticResult(passed=False, feedback=[DEFAULT_FEEDBACK])
    critic = CriticResult.model_validate(raw)
    for field_name in ("feedback", "missing_requirements", "unsupported_claims", "required_timestamps"):
        value = getattr(critic, field_name)
        if value is None:
            setattr(critic, field_name, [])
    return critic


def structure_issues(result: AnalysisResult, profile: ModeProfile) -> list[str]:
    """结构完整性:缺 title/conclusions/evidence 或缺模式必需 section keys。"""
    issues: list[str] = []
    if not result.title.strip():
        issues.append("缺少 title,请给出分析标题")
    if not result.conclusions:
        issues.append("缺少 conclusions,请给出至少一条结论")
    if not result.evidence:
        issues.append("缺少 evidence,每条结论必须绑定时间戳证据")
    missing_keys = [k for k in profile.required_section_keys if k not in result.section_keys()]
    if missing_keys:
        issues.append(f"缺少模式必需的 sections: {', '.join(missing_keys)}")
    return issues


def clamp_critique(
    critic: CriticResult,
    *,
    result: AnalysisResult,
    profile: ModeProfile,
    segments: list[VideoSegment],
) -> CriticResult:
    """normalize 之后的完整护栏链:结构钳制 → 证据核验 → 一致性钳制。"""
    feedback = list(critic.feedback)
    unsupported = list(critic.unsupported_claims)
    required_ts = list(critic.required_timestamps)
    missing = list(critic.missing_requirements)

    # 1) 结构钳制
    issues = structure_issues(result, profile)
    feedback.extend(issues)

    # 2) 纯代码证据核验(LLM Critic 只做语义层)
    report: VerifyReport = verify_result(result, segments)
    feedback.extend(report.unsupported_evidence)
    unsupported.extend(report.unsupported_evidence)
    unsupported.extend(report.claim_mismatches)
    unsupported.extend(report.uncovered_conclusions)
    for ev in result.evidence:
        # 无法核验的证据时间戳进入定向重检索钩子
        if any(ev.timestamp_ms == ts for ts in required_ts):
            continue
        from dovideo.core.evidence import evidence_supported

        if not evidence_supported(ev, segments):
            required_ts.append(ev.timestamp_ms)

    passed = critic.passed and not issues and not report.messages()

    # 3) 一致性钳制:报 passed 却有问题 → 强制 false;报 false 却无理由 → 默认 feedback
    if passed and (feedback or unsupported or missing or required_ts):
        passed = False
    if not passed and not feedback:
        feedback = [DEFAULT_FEEDBACK]

    return CriticResult(
        passed=passed,
        feedback=feedback,
        missing_requirements=missing,
        unsupported_claims=unsupported,
        required_timestamps=required_ts,
    )


# ── 三角色 LLM 调用 ────────────────────────────────────────────────────
def _plan_valid(plan: AgentPlan) -> bool:
    return not validate_plan(plan)


def _parse_plan(payload: dict) -> AgentPlan:
    tasks = payload.get("tasks")
    if isinstance(tasks, str):
        tasks = [tasks]
    plan = AgentPlan(
        understoodGoal=str(payload.get("understoodGoal", "")),
        tasks=[str(t) for t in (tasks or [])],
    )
    return plan


async def make_plan(
    llm: LLMClient,
    context_text: str,
    goal: str,
    profile: ModeProfile,
    *,
    budget: AgentExecutionBudget,
    telemetry: Telemetry,
) -> AgentPlan:
    payload = await structured_chat(
        llm,
        plan_messages(context_text, goal, profile),
        timeout_s=30.0,
        budget=budget,
        telemetry=telemetry,
    )
    plan = _parse_plan(payload)
    if _plan_valid(plan):
        return plan

    # 结构校验失败 → repair 一次
    telemetry.incr("planStructureRepairs")
    try:
        payload = await structured_chat(
            llm,
            plan_repair_messages(context_text, goal, json.dumps(payload, ensure_ascii=False)),
            timeout_s=30.0,
            budget=budget,
            telemetry=telemetry,
        )
        plan = _parse_plan(payload)
    except (RetriableLLMError, PermanentLLMError, StructuredParseError) as exc:
        raise PermanentLLMError(f"计划修复失败: {exc}") from exc
    if _plan_valid(plan):
        return plan
    raise PermanentLLMError(f"计划结构校验失败: {validate_plan(plan)}")


async def revise_plan(
    llm: LLMClient,
    context_text: str,
    goal: str,
    profile: ModeProfile,
    current_plan: AgentPlan,
    critique: CriticResult,
    *,
    budget: AgentExecutionBudget,
    telemetry: Telemetry,
) -> AgentPlan:
    """增量修订:保留仍然有效的任务,只补充遗漏;失败回退旧 plan。"""
    try:
        payload = await structured_chat(
            llm,
            plan_messages(
                context_text,
                goal,
                profile,
                previous_plan=current_plan.model_dump(by_alias=True),
                critique=critique,
            ),
            timeout_s=30.0,
            budget=budget,
            telemetry=telemetry,
        )
        plan = _parse_plan(payload)
        if _plan_valid(plan):
            return plan
    except (RetriableLLMError, PermanentLLMError, StructuredParseError):
        pass
    telemetry.incr("planRevisionFallbacks")
    return current_plan


def _parse_result(payload: dict) -> AnalysisResult:
    evidence = [
        Evidence.model_validate(ev) for ev in (payload.get("evidence") or []) if isinstance(ev, dict)
    ]
    sections = [
        ResultSection.model_validate(s) for s in (payload.get("sections") or []) if isinstance(s, dict)
    ]
    return AnalysisResult(
        title=str(payload.get("title", "")),
        conclusions=[str(c) for c in (payload.get("conclusions") or [])],
        evidence=evidence,
        suggestions=[str(s) for s in (payload.get("suggestions") or [])],
        sections=sections,
    )


async def execute(
    llm: LLMClient,
    context_text: str,
    goal: str,
    profile: ModeProfile,
    plan: AgentPlan,
    *,
    budget: AgentExecutionBudget,
    telemetry: Telemetry,
    previous_result: AnalysisResult | None = None,
    previous_critique: CriticResult | None = None,
) -> AnalysisResult:
    payload = await structured_chat(
        llm,
        execute_messages(
            context_text,
            goal,
            profile,
            plan.model_dump(by_alias=True),
            previous_result=(
                previous_result.model_dump(by_alias=True) if previous_result else None
            ),
            previous_critique=previous_critique,
        ),
        timeout_s=60.0,
        budget=budget,
        telemetry=telemetry,
    )
    return _parse_result(payload)


async def critique(
    llm: LLMClient,
    context_text: str,
    goal: str,
    profile: ModeProfile,
    plan: AgentPlan,
    result: AnalysisResult,
    *,
    budget: AgentExecutionBudget,
    telemetry: Telemetry,
) -> CriticResult:
    payload = await structured_chat(
        llm,
        critique_messages(
            context_text, goal, profile, plan.model_dump(by_alias=True), result.model_dump(by_alias=True)
        ),
        timeout_s=60.0,
        budget=budget,
        telemetry=telemetry,
    )
    return normalize_critique(payload)


# ── 主循环 ─────────────────────────────────────────────────────────────
class AgentLoop:
    def __init__(
        self,
        *,
        llm: LLMClient,
        store: CheckpointStore,
        settings: Settings,
        telemetry: Telemetry | None = None,
        events: StageEvent | None = None,
        budget: AgentExecutionBudget | None = None,
        cost_fn=None,
    ) -> None:
        self.llm = llm
        self.store = store
        self.settings = settings
        self.telemetry = telemetry or Telemetry()
        self.events = events or (lambda stage, message: None)
        self.budget = budget or AgentExecutionBudget(
            max_duration_ms=settings.agent_max_duration_ms,
            max_tokens=settings.agent_max_estimated_tokens,
            max_cost=settings.agent_max_estimated_cost,
            input_price_per_million=settings.llm_input_price_per_million,
            output_price_per_million=settings.llm_output_price_per_million,
        )
        self.cost_fn = cost_fn

    def _structured(self, messages: list[dict[str, str]], timeout: float):
        return structured_chat(
            self.llm,
            messages,
            timeout_s=timeout,
            budget=self.budget,
            telemetry=self.telemetry,
            cost_fn=self.cost_fn,
        )

    def _check_budget(self, stage: str) -> None:
        """阶段边界预算检查(原版 checkBudget)。"""
        try:
            self.budget.check(
                used_tokens=self.telemetry.total_tokens, used_cost=self.telemetry.estimated_cost
            )
        except Exception:
            raise
        _ = stage

    async def run(
        self,
        media_id: str,
        full_context: VideoContext,
        goal: str,
        mode: AnalysisMode = AnalysisMode.GENERAL,
        *,
        selected: list[VideoSegment] | None = None,
        chunks: list | None = None,
        store_handle=None,
        media_ref: str = "",
    ) -> LoopOutcome:
        started = time.monotonic()
        profile = get_profile(mode)
        digest = goal_digest(goal, mode.value)
        plan_key = f"goal:{digest}:plan"
        critic_key = f"goal:{digest}:criticState"
        result_key = f"goal:{digest}:result"

        def emit(stage: str, message: str = "") -> None:
            self.telemetry.record_stage(stage, int((time.monotonic() - started) * 1000))
            self.events(stage, message)

        self._check_budget("AGENT_LOOP")

        # ── 恢复性短路 1: 终态 checkpoint 命中(带自修复)──────────────
        _, critic_state_raw = self.store.load(media_id, critic_key)
        saved_plan_raw = self.store.load(media_id, plan_key)[1]
        resume_state: AgentState | None = None
        if critic_state_raw:
            resume_state = AgentState.model_validate(critic_state_raw)
            if (
                resume_state.result is not None
                and resume_state.critique is not None
                and (resume_state.critique.passed or resume_state.round >= self.settings.agent_max_rounds)
            ):
                saved_plan = AgentPlan.model_validate(saved_plan_raw) if saved_plan_raw else None
                if (
                    saved_plan is not None
                    and _plan_valid(saved_plan)
                    and not structure_issues(resume_state.result, profile)
                ):
                    self.telemetry.incr("terminalCheckpointHits")
                    emit("ANALYSIS_COMPLETED", "命中终态检查点,直接复用")
                    return LoopOutcome(
                        result=resume_state.result,
                        passed=resume_state.critique.passed,
                        rounds=resume_state.round,
                        warnings=[] if resume_state.critique.passed else list(resume_state.critique.feedback),
                    )
                # 脏 checkpoint → round 归零重跑,防止永久短路
                self.telemetry.incr("invalidTerminalCheckpointRepairs")
                resume_state = None
            elif resume_state.result is not None and resume_state.critique is None:
                # ── 恢复性短路 2: Executor 草稿(重试直接从 Critic 续跑)──
                self.telemetry.incr("criticCheckpointResumes")

        # ── Planner ────────────────────────────────────────────────
        if saved_plan_raw is not None:
            plan = AgentPlan.model_validate(saved_plan_raw)
        else:
            context_text = render_segments(selected or full_context.segments, self.settings.max_context_chars)
            plan = await make_plan(
                self.llm, context_text, goal, profile, budget=self.budget, telemetry=self.telemetry
            )
            self.store.save(media_id, plan_key, plan.model_dump(by_alias=True), stage="PLAN_COMPLETED")
            emit("PLAN_COMPLETED", plan.understood_goal)

        max_rounds = max(1, self.settings.agent_max_rounds)
        warnings: list[str] = []
        stage_history: list[str] = []
        final_result: AnalysisResult | None = None
        final_critic: CriticResult | None = None
        rounds_used = 0
        start_round = 1
        draft: AnalysisResult | None = None
        prev_result: AnalysisResult | None = None
        prev_critique: CriticResult | None = None
        if resume_state is not None and resume_state.result is not None:
            if resume_state.critique is None:
                # 草稿恢复:直接续跑 Critic
                start_round = max(1, resume_state.round)
                draft = resume_state.result
            else:
                # 带 critique 的中断恢复:下一轮带着上一轮反馈重新执行
                start_round = min(resume_state.round + 1, max_rounds)
                prev_result = resume_state.result
                prev_critique = resume_state.critique

        current_selected = list(selected or full_context.segments)

        for round_no in range(start_round, max_rounds + 1):
            self._check_budget(f"ROUND_{round_no}")

            # ── Executor(草稿先落盘再 Critic)──────────────────────
            if draft is not None:
                result = draft
                draft = None  # 只消费一次
            else:
                emit("EXECUTOR_STARTED")
                t0 = time.monotonic()
                context_text = render_segments(current_selected, self.settings.max_context_chars)
                result = await execute(
                    self.llm,
                    context_text,
                    goal,
                    profile,
                    plan,
                    budget=self.budget,
                    telemetry=self.telemetry,
                    previous_result=prev_result,
                    previous_critique=prev_critique,
                )
                prev_result, prev_critique = None, None  # 只在恢复后的第一轮携带
                self.telemetry.record_stage("EXECUTOR", int((time.monotonic() - t0) * 1000))
                self.store.save(
                    media_id,
                    critic_key,
                    AgentState(round=round_no, plan=plan, result=result, critique=None).model_dump(by_alias=True),
                    stage="EXECUTOR_COMPLETED",
                )
                emit("EXECUTOR_COMPLETED")

            self._check_budget(f"POST_EXECUTE_{round_no}")

            # ── Critic + 确定性护栏 ────────────────────────────────
            emit("CRITIC_STARTED")
            t1 = time.monotonic()
            context_text = render_segments(current_selected, self.settings.max_context_chars)
            raw_critic = await critique(
                self.llm, context_text, goal, profile, plan, result,
                budget=self.budget, telemetry=self.telemetry,
            )
            critic = clamp_critique(raw_critic, result=result, profile=profile, segments=current_selected)
            self.telemetry.record_stage("CRITIC", int((time.monotonic() - t1) * 1000))
            self.telemetry.incr("criticRounds")
            rounds_used = round_no
            stage_history.append("CRITIC_PASSED" if critic.passed else "CRITIC_RETRY_REQUIRED")
            self.store.save(
                media_id,
                critic_key,
                AgentState(round=round_no, plan=plan, result=result, critique=critic).model_dump(by_alias=True),
                stage="CRITIC_PASSED" if critic.passed else "CRITIC_RETRY_REQUIRED",
            )
            emit("CRITIC_PASSED" if critic.passed else "CRITIC_RETRY_REQUIRED")
            final_result, final_critic = result, critic

            if critic.passed:
                self.telemetry.incr("criticPassed")
                break

            # ── 定向补证据 + 增量 replan(未到最后一轮)────────────
            if round_no < max_rounds:
                if requires_evidence_refresh(critic):
                    t2 = time.monotonic()
                    current_selected = await context_for_retry(
                        full_context,
                        current_selected,
                        critic,
                        goal,
                        llm=self.llm,
                        settings=self.settings,
                        telemetry=self.telemetry,
                        budget=self.budget,
                        chunks=chunks,
                        store=store_handle,
                        media_id=media_id,
                    )
                    self.telemetry.record_stage("EVIDENCE_REFRESH", int((time.monotonic() - t2) * 1000))
                    emit("EVIDENCE_REFRESHED", f"补充 {len(current_selected)} 段定向证据")
                plan = await revise_plan(
                    self.llm,
                    render_segments(current_selected, self.settings.max_context_chars),
                    goal,
                    profile,
                    plan,
                    critic,
                    budget=self.budget,
                    telemetry=self.telemetry,
                )
                self.store.save(media_id, plan_key, plan.model_dump(by_alias=True), stage="PLAN_COMPLETED")
                emit("PLAN_COMPLETED", "计划已按审校反馈修订")

        assert final_result is not None
        passed = bool(final_critic and final_critic.passed)
        if not passed:
            warnings = list(final_critic.feedback) if final_critic else []
        final_stage = "ANALYSIS_COMPLETED" if passed else "ANALYSIS_COMPLETED_WITH_WARNINGS"
        self.store.save(
            media_id,
            result_key,
            final_result.model_dump(by_alias=True),
            stage=final_stage,
        )
        # 原版 saveResult 同步更新阶段检查点
        self.store.save_stage(media_id, digest, final_stage)
        emit(final_stage)
        outcome = LoopOutcome(
            result=final_result,
            passed=passed,
            rounds=rounds_used,
            warnings=warnings,
            stage_history=stage_history,
            telemetry=self.telemetry.to_dict(),
        )
        return outcome
