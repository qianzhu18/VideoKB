"""预算三闸门(移植自 service/AgentExecutionBudget.java)。

- 时长:截止时间戳,模型调用超时 = min(模型超时, 剩余预算)
- Token:累计估算超限即熔断
- 成本:max=0 表示禁用;启用时必须配置输入/输出单价
超预算抛 BudgetExhaustedError → 终态 BUDGET_EXHAUSTED,不重试。
"""

from __future__ import annotations

import time


class BudgetExhaustedError(Exception):
    """预算耗尽;消息含具体原因。调用方不得重试。"""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


class AgentExecutionBudget:
    def __init__(
        self,
        *,
        max_duration_ms: int,
        max_tokens: int,
        max_cost: float = 0.0,
        input_price_per_million: float = 0.0,
        output_price_per_million: float = 0.0,
        clock=time.monotonic,
    ) -> None:
        if max_cost > 0 and (input_price_per_million <= 0 and output_price_per_million <= 0):
            # 原版 fail-fast:启用成本闸门必须同时配置单价
            raise ValueError("AGENT_MAX_ESTIMATED_COST 启用时必须配置输入/输出单价")
        self._max_duration_ms = max_duration_ms
        self._max_tokens = max_tokens
        self._max_cost = max_cost
        self._input_price = input_price_per_million
        self._output_price = output_price_per_million
        self._clock = clock
        self._deadline = clock() + max_duration_ms / 1000.0

    def remaining_ms(self) -> int:
        return max(0, int((self._deadline - self._clock()) * 1000))

    def model_timeout_seconds(self, model_timeout_seconds: float) -> float:
        """模型调用超时 = min(模型超时, 剩余预算);剩余为 0 时给一个最小值让调用立即失败。"""
        remaining_s = self.remaining_ms() / 1000.0
        return max(0.001, min(model_timeout_seconds, remaining_s))

    def estimate_cost(self, input_tokens: int, output_tokens: int) -> float:
        return (
            input_tokens * self._input_price / 1_000_000
            + output_tokens * self._output_price / 1_000_000
        )

    def check(
        self,
        *,
        used_tokens: int = 0,
        used_cost: float = 0.0,
        estimated_extra_tokens: int = 0,
    ) -> None:
        """阶段边界检查:超时长 / Token 超限 / 成本超限 → BudgetExhaustedError。"""
        if self.remaining_ms() <= 0:
            raise BudgetExhaustedError(f"执行时长超过预算 {self._max_duration_ms}ms")
        total_tokens = used_tokens + estimated_extra_tokens
        if self._max_tokens > 0 and total_tokens > self._max_tokens:
            raise BudgetExhaustedError(
                f"预估 Token 超过预算: {total_tokens} > {self._max_tokens}"
            )
        if self._max_cost > 0 and used_cost > self._max_cost:
            raise BudgetExhaustedError(f"预估成本超过预算: {used_cost:.4f} > {self._max_cost}")
