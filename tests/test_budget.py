"""预算三闸门。"""

import pytest

from dovideo.core.budget import AgentExecutionBudget, BudgetExhaustedError


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    def advance(self, s: float):
        self.t += s


def test_duration_gate():
    clock = FakeClock()
    b = AgentExecutionBudget(max_duration_ms=1000, max_tokens=1_000_000, clock=clock)
    b.check()
    clock.advance(1.1)
    with pytest.raises(BudgetExhaustedError):
        b.check()


def test_model_timeout_clamped_to_remaining():
    clock = FakeClock()
    b = AgentExecutionBudget(max_duration_ms=10_000, max_tokens=1_000_000, clock=clock)
    assert b.model_timeout_seconds(300) == 10.0
    clock.advance(9.99)
    assert b.model_timeout_seconds(300) < 0.1


def test_token_gate():
    b = AgentExecutionBudget(max_duration_ms=60_000, max_tokens=100)
    b.check(used_tokens=90)
    with pytest.raises(BudgetExhaustedError):
        b.check(used_tokens=101)


def test_cost_gate_requires_prices():
    with pytest.raises(ValueError):
        AgentExecutionBudget(max_duration_ms=1000, max_tokens=10, max_cost=1.0)


def test_cost_gate_enabled():
    b = AgentExecutionBudget(
        max_duration_ms=60_000,
        max_tokens=1_000_000,
        max_cost=1.0,
        input_price_per_million=2.0,
        output_price_per_million=8.0,
    )
    assert b.estimate_cost(500_000, 0) == 1.0  # 0.5M * $2/M
    b.check(used_cost=0.5)
    with pytest.raises(BudgetExhaustedError):
        b.check(used_cost=1.5)
