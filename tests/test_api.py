"""FastAPI 契约:信封 / 202 受理 / 状态收敛 / SSE 终态。"""

import asyncio
import json

import httpx
import pytest
from httpx import ASGITransport

from dovideo.api.app import AppState, EventBus, create_app
from dovideo.core.analysis import AnalysisMode
from dovideo.core.fake import FakeLLM
from dovideo.jobs.worker import Deps, InlineJobRunner

from test_loop import FAKE_INTENT, FAKE_PLAN, FAKE_SUMMARY, GOOD_RESULT, BAD_RESULT, CRITIQUE_FAIL, CRITIQUE_PASS


@pytest.fixture
def client(settings, context):
    async def builder(media_id: str, goal: str):
        return context

    bus = EventBus()
    llm = FakeLLM(
        plans=[FAKE_PLAN],
        executes=[GOOD_RESULT],
        critiques=[{"passed": True}],
        mode={"mode": "LEARNING", "reason": "学习课程"},
        summaries=[FAKE_SUMMARY],
        intents=[FAKE_INTENT],
    )
    deps = Deps(settings=settings, llm=llm, store=__import__("dovideo.checkpoints.store", fromlist=["CheckpointStore"]).CheckpointStore(settings.db_path), context_builder=builder)
    deps.broadcast = lambda media_id, digest, stage, message: bus.publish(
        f"{media_id}:{digest}", {"state": _state_of(stage), "stage": stage, "message": message}
    )
    state = AppState(deps=deps, runner=InlineJobRunner(deps, sleep_fn=_zero), bus=bus)
    app = create_app(state=state)
    return httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://t"), state


async def _zero(_: float) -> None:
    return None


def _state_of(stage: str) -> str:
    if stage.startswith(("COMPLETED", "ANALYSIS_COMPLETED")):
        return "COMPLETED"
    if stage in ("FAILED", "DEAD_LETTERED", "BUDGET_EXHAUSTED"):
        return "FAILED"
    return "PROCESSING"


async def test_health(client):
    http, _ = client
    r = await http.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body == {"code": 0, "message": "success", "data": "UP"}


async def test_route_mode(client):
    http, _ = client
    r = await http.post("/analysis/route", json={"goal": "帮我学习这节课"})
    assert r.status_code == 200
    assert r.json()["data"]["mode"] == "LEARNING"


async def test_submit_status_sse_flow(client, context):
    http, _ = client
    goal = context.user_goal
    # 1) 受理 → 202
    r = await http.post("/analysis/ai", json={"media_id": "m1", "goal": goal, "mode": "LEARNING"})
    assert r.status_code == 202
    assert r.json()["data"]["accepted"] is True

    # 2) 轮询状态直到终态
    state_data = None
    for _ in range(200):
        r = await http.get(
            "/analysis/analysis-status", params={"mediaId": "m1", "goal": goal, "mode": "LEARNING"}
        )
        state_data = r.json()["data"]
        if state_data["state"] in ("COMPLETED", "FAILED"):
            break
        await asyncio.sleep(0.01)
    assert state_data["state"] == "COMPLETED"
    assert state_data["result"]["title"]

    # 3) SSE:终态后首事件即 COMPLETED 并结束流
    lines: list[str] = []
    async with http.stream(
        "GET", "/analysis/analysis-events", params={"mediaId": "m1", "goal": goal, "mode": "LEARNING"}
    ) as resp:
        assert resp.headers["content-type"].startswith("text/event-stream")
        async for line in resp.aiter_lines():
            lines.append(line)
            if line.strip() == "" and any(l.startswith("data:") for l in lines):
                break
    data_lines = [l for l in lines if l.startswith("data:")]
    assert data_lines, "SSE 未输出事件"
    payload = json.loads(data_lines[0][len("data:"):].strip())
    assert payload["state"] == "COMPLETED"

    # 4) 重复提交 → 复用(200 reused)
    r = await http.post("/analysis/ai", json={"media_id": "m1", "goal": goal, "mode": "LEARNING"})
    assert r.json()["data"]["reused"] is True

    # 5) 计划可查
    r = await http.get(
        "/analysis/agent-plan", params={"mediaId": "m1", "goal": goal, "mode": "LEARNING"}
    )
    assert r.json()["data"]["understoodGoal"]


async def test_invalid_mode_rejected(client):
    http, _ = client
    r = await http.post("/analysis/ai", json={"media_id": "m1", "goal": "g", "mode": "BOGUS"})
    assert r.status_code == 400
