"""模式/计划结构与 goalDigest 键体系。"""

import pytest

from dovideo.core.analysis import (
    AgentPlan,
    AnalysisMode,
    get_profile,
    validate_plan,
)
from dovideo.checkpoints.store import goal_digest


def test_mode_from_nullable_falls_back():
    assert AnalysisMode.from_nullable(None) == AnalysisMode.GENERAL
    assert AnalysisMode.from_nullable("learning") == AnalysisMode.LEARNING
    assert AnalysisMode.from_nullable("bogus") == AnalysisMode.GENERAL


def test_mode_from_request_raises_on_bogus():
    with pytest.raises(ValueError):
        AnalysisMode.from_request("bogus")
    assert AnalysisMode.from_request("review") == AnalysisMode.REVIEW


def test_mode_profiles_complete():
    for mode in AnalysisMode:
        get_profile(mode)  # 不应 KeyError


def test_validate_plan_bounds():
    ok = AgentPlan(understoodGoal="g", tasks=["任务一"])
    assert validate_plan(ok) == []
    too_many = AgentPlan(understoodGoal="g", tasks=[f"t{i}" for i in range(6)])
    assert any("任务数量" in i for i in validate_plan(too_many))
    empty = AgentPlan(understoodGoal="", tasks=[])
    assert validate_plan(empty)


def test_goal_digest_separator_prevents_collision():
    # GENERAL 模式:digest = sha256(goal)
    assert goal_digest("目标x", "GENERAL") == goal_digest("目标x", "GENERAL")
    # 分隔符使"LEARN"+"ING目标x"与"LEARNING"+"目标x"不碰撞(无分隔符时 sha256 拼接串相同)
    a = goal_digest("ING目标x", "LEARN")
    b = goal_digest("目标x", "LEARNING")
    assert a != b
    # 非 GENERAL 与 GENERAL 显式不同
    assert goal_digest("目标x", "LEARNING") != goal_digest("目标x", "GENERAL")
