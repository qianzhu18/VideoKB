"""阶段遥测(移植自 service/AgentTelemetry.java):阶段耗时 + 30+ 计数器 + 成本。"""

from __future__ import annotations

import itertools
import time
from dataclasses import dataclass, field
from typing import Any

_TRACE_SEQ = itertools.count(1)


def new_trace_id() -> str:
    return f"trace-{next(_TRACE_SEQ):06d}-{int(time.time())}"


@dataclass(slots=True)
class Telemetry:
    trace_id: str = field(default_factory=new_trace_id)
    stage_durations_ms: dict[str, int] = field(default_factory=dict)
    counters: dict[str, int] = field(default_factory=dict)
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost: float = 0.0

    def incr(self, name: str, delta: int = 1) -> None:
        self.counters[name] = self.counters.get(name, 0) + delta

    def record_stage(self, stage: str, duration_ms: int) -> None:
        # 同一阶段多次执行取累计时长(与原版一致逐事件记录)
        self.stage_durations_ms[stage] = self.stage_durations_ms.get(stage, 0) + duration_ms

    def model_call(
        self,
        *,
        input_tokens: int,
        output_tokens: int,
        cost: float = 0.0,
        budget_check: bool = True,
    ) -> None:
        self.incr("modelCalls")
        self.input_tokens += input_tokens
        self.output_tokens += output_tokens
        self.estimated_cost += cost
        if not budget_check:
            self.incr("budgetCheckSkipped")

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens

    def to_dict(self) -> dict[str, Any]:
        return {
            "traceId": self.trace_id,
            "stageDurationsMs": dict(self.stage_durations_ms),
            "counters": dict(self.counters),
            "inputTokensEstimated": self.input_tokens,
            "outputTokensEstimated": self.output_tokens,
            "estimatedCost": round(self.estimated_cost, 6),
        }
