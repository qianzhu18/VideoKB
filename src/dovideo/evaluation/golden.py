"""离线金标评测(移植自 OfflineAgentEvaluationRunner + golden-video-tasks.json)。

通过标准(原版):structuredValid && claimEvidenceSupportRate ≥ 0.8 && keywordCoverage ≥ 0.8
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

from dovideo.agent.loop import LoopOutcome
from dovideo.checkpoints.store import CheckpointStore
from dovideo.config import Settings
from dovideo.core.analysis import AnalysisMode, get_profile
from dovideo.core.evidence import verify_result
from dovideo.core.llm import LLMClient
from dovideo.core.models import VideoContext
from dovideo.core.textnorm import normalize
from dovideo.jobs.worker import Deps, run_analysis_job


@dataclass(slots=True)
class GoldenCase:
    case_id: str
    goal: str
    mode: AnalysisMode = AnalysisMode.GENERAL
    context: VideoContext = None  # type: ignore[assignment]
    expected_keywords: list[str] = field(default_factory=list)


@dataclass(slots=True)
class CaseReport:
    case_id: str
    structured_valid: bool
    claim_support_rate: float
    keyword_coverage: float
    passed: bool
    detail: dict

    def to_dict(self) -> dict:
        return {
            "caseId": self.case_id,
            "structuredValid": self.structured_valid,
            "claimEvidenceSupportRate": round(self.claim_support_rate, 4),
            "keywordCoverage": round(self.keyword_coverage, 4),
            "passed": self.passed,
            "detail": self.detail,
        }


def _structured_valid(outcome: LoopOutcome, mode: AnalysisMode) -> bool:
    result = outcome.result
    profile = get_profile(mode)
    missing_keys = [k for k in profile.required_section_keys if k not in result.section_keys()]
    return bool(result.title and result.conclusions and result.evidence) and not missing_keys


def _claim_support_rate(outcome: LoopOutcome, context: VideoContext) -> float:
    report = verify_result(outcome.result, context.segments)
    total = len(outcome.result.evidence) + len(outcome.result.conclusions)
    bad = len(report.unsupported_evidence) + len(report.claim_mismatches) + len(report.uncovered_conclusions)
    if total == 0:
        return 0.0
    return max(0.0, 1.0 - bad / total)


def _keyword_coverage(outcome: LoopOutcome, expected_keywords: list[str]) -> float:
    if not expected_keywords:
        return 1.0
    haystack = normalize(
        " ".join(
            [outcome.result.title, *outcome.result.conclusions, *outcome.result.suggestions]
            + [item for s in outcome.result.sections for item in s.items]
        )
    )
    hits = sum(1 for k in expected_keywords if normalize(k) in haystack)
    return hits / len(expected_keywords)


async def evaluate_case(case: GoldenCase, deps: Deps) -> CaseReport:
    outcome = await run_analysis_job(
        deps, case.case_id, case.goal, case.mode, context=case.context
    )
    structured_ok = _structured_valid(outcome, case.mode)
    claim_rate = _claim_support_rate(outcome, case.context)
    keyword_rate = _keyword_coverage(outcome, case.expected_keywords)
    passed = structured_ok and claim_rate >= 0.8 and keyword_rate >= 0.8
    return CaseReport(
        case_id=case.case_id,
        structured_valid=structured_ok,
        claim_support_rate=claim_rate,
        keyword_coverage=keyword_rate,
        passed=passed,
        detail=outcome.telemetry or {},
    )


async def run_golden(cases: list[GoldenCase], deps: Deps) -> dict:
    reports = [await evaluate_case(c, deps) for c in cases]
    passed = sum(1 for r in reports if r.passed)
    return {
        "total": len(reports),
        "passed": passed,
        "passRate": passed / len(reports) if reports else 0.0,
        "cases": [r.to_dict() for r in reports],
    }


def load_golden_file(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


__all__ = [
    "GoldenCase",
    "CaseReport",
    "evaluate_case",
    "run_golden",
    "load_golden_file",
    "CheckpointStore",
    "Settings",
    "LLMClient",
]
