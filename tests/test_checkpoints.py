"""Checkpoint 存储:键体系 / 两段式修订 / 提交幂等 / 失败台账脱敏。"""

from dovideo.checkpoints.store import goal_digest


def test_save_load_roundtrip(store):
    store.save("m1", "goal:x:plan", {"tasks": ["a"]}, stage="PLAN_COMPLETED")
    stage, payload = store.load("m1", "goal:x:plan")
    assert stage == "PLAN_COMPLETED"
    assert payload == {"tasks": ["a"]}


def test_upsert_overwrites(store):
    store.save("m1", "k", {"v": 1})
    store.save("m1", "k", {"v": 2}, stage="S")
    assert store.load("m1", "k") == ("S", {"v": 2})


def test_two_phase_revision(store):
    digest = goal_digest("目标", "LEARNING")
    prefix = f"goal:{digest}:"
    # 旧计划在库
    store.save("m1", prefix + "plan", {"tasks": ["旧"]}, stage="PLAN_COMPLETED")
    # 用户提交修订
    store.save_revision("m1", digest, {"tasks": ["新"]})
    plan = store.begin_staged_revision("m1", digest)
    assert plan == {"tasks": ["新"]}
    # 修订应用时按前缀删除了旧 checkpoint,并写入新计划
    stage, payload = store.load("m1", prefix + "plan")
    assert payload == {"tasks": ["新"]} and stage == "REVISION_APPLIED"
    # 幂等:再次 begin 直接返回已应用计划
    assert store.begin_staged_revision("m1", digest) == {"tasks": ["新"]}
    store.complete_revision("m1", digest)
    assert store.load("m1", prefix + "revision") == (None, None)


def test_active_claim_idempotency(store):
    assert store.claim_active("m1", "d1") is True
    assert store.claim_active("m1", "d1") is False  # 已占用 → 409 语义
    assert store.has_active("m1", "d1")
    store.release_active("m1", "d1")
    assert store.claim_active("m1", "d1") is True


def test_failure_sanitizes_secrets(store):
    store.record_failure("m1", "d1", "GENERAL", "FAILED", "PermanentLLMError",
                         "Authorization: bearer abc.def  api_key=xyz  sk-1234567890")
    rows = store.list_failures()
    msg = rows[0]["errorMessage"]
    assert "abc.def" not in msg and "xyz" not in msg and "1234567890" not in msg
    assert "****" in msg
