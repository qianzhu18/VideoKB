"""Checkpoint 存储(移植自 service/AgentCheckpointService.java)。

- SQL 为恢复真源,Redis 只承担热读取(可选;缓存失效不影响恢复)
- 键体系:
  内容级: media:context / media:chunks / media:stage (跨目标复用)
  目标级: goal:{digest}:plan|result|criticState|stage|revision
  digest = sha256(goal)  (GENERAL)
         = sha256(mode + '␟' + goal)  (非 GENERAL,U+2419 防拼接碰撞)
- 两段式用户修订: save_revision(applied=false) → begin_staged_revision(按前缀删旧
  checkpoint 后写入修订计划) → complete_revision
- 幂等键: claim_active/release_active (原版 analysis:active:{contentHash}:{goalDigest})
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

SEPARATOR = "\u2419"  # ␟ 单元分隔符,防止“模式名+目标”与真实目标碰撞


def goal_digest(goal: str, mode: str = "GENERAL") -> str:
    material = goal if mode == "GENERAL" else f"{mode}{SEPARATOR}{goal}"
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


class CheckpointStore:
    def __init__(self, db_path: str = "dovideo.db", redis_client=None) -> None:
        self.db_path = db_path
        self._redis = redis_client
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS checkpoints (
                media_id TEXT NOT NULL,
                checkpoint_key TEXT NOT NULL,
                stage TEXT,
                payload TEXT,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (media_id, checkpoint_key)
            );
            CREATE TABLE IF NOT EXISTS active_tasks (
                idempotency_key TEXT PRIMARY KEY,
                media_id TEXT NOT NULL,
                goal_digest TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS failed_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id TEXT,
                goal_digest TEXT,
                mode TEXT,
                stage TEXT,
                error_type TEXT,
                error_message TEXT,
                created_at TEXT NOT NULL
            );
            """
        )
        self._conn.commit()

    # ── 基础读写(SQL 真源)──────────────────────────────────────────
    def save(self, media_id: str, checkpoint_key: str, payload: dict | None, stage: str | None = None) -> None:
        now = datetime.now(timezone.utc).isoformat()
        self._conn.execute(
            "INSERT INTO checkpoints(media_id, checkpoint_key, stage, payload, updated_at) "
            "VALUES(?,?,?,?,?) ON CONFLICT(media_id, checkpoint_key) DO UPDATE SET "
            "stage=excluded.stage, payload=excluded.payload, updated_at=excluded.updated_at",
            (media_id, checkpoint_key, stage, json.dumps(payload, ensure_ascii=False) if payload is not None else None, now),
        )
        self._conn.commit()
        self._cache_put(media_id, checkpoint_key, stage, payload)

    def load(self, media_id: str, checkpoint_key: str) -> tuple[str | None, dict | None]:
        cached = self._cache_get(media_id, checkpoint_key)
        if cached is not None:
            return cached
        row = self._conn.execute(
            "SELECT stage, payload FROM checkpoints WHERE media_id=? AND checkpoint_key=?",
            (media_id, checkpoint_key),
        ).fetchone()
        if row is None:
            return None, None
        stage, payload = row[0], (json.loads(row[1]) if row[1] else None)
        self._cache_put(media_id, checkpoint_key, stage, payload)
        return stage, payload

    def delete_by_prefix(self, media_id: str, prefix: str) -> int:
        cur = self._conn.execute(
            "DELETE FROM checkpoints WHERE media_id=? AND checkpoint_key LIKE ?",
            (media_id, prefix + "%"),
        )
        self._conn.commit()
        self._cache_invalidate_prefix(media_id, prefix)
        return cur.rowcount

    # ── 阶段状态机 ──────────────────────────────────────────────────
    def save_stage(self, media_id: str, digest: str, stage: str) -> None:
        self.save(media_id, f"goal:{digest}:stage", None, stage=stage)

    def load_stage(self, media_id: str, digest: str) -> str | None:
        stage, _ = self.load(media_id, f"goal:{digest}:stage")
        return stage

    # ── 两段式修订 ──────────────────────────────────────────────────
    def save_revision(self, media_id: str, digest: str, plan: dict) -> None:
        self.save(media_id, f"goal:{digest}:revision", {"plan": plan, "applied": False})

    def begin_staged_revision(self, media_id: str, digest: str) -> dict | None:
        """消费者接手时调用:已应用 → 直接返回;未应用 → 按前缀删旧 checkpoint、写入修订计划。"""
        stage, payload = self.load(media_id, f"goal:{digest}:revision")
        if payload is None:
            return None
        if payload.get("applied"):
            return payload.get("plan")
        plan = payload.get("plan")
        self.delete_by_prefix(media_id, f"goal:{digest}:")
        self.save(media_id, f"goal:{digest}:plan", plan, stage="REVISION_APPLIED")
        self.save(media_id, f"goal:{digest}:revision", {"plan": plan, "applied": True})
        return plan

    def complete_revision(self, media_id: str, digest: str) -> None:
        self._conn.execute(
            "DELETE FROM checkpoints WHERE media_id=? AND checkpoint_key=?",
            (media_id, f"goal:{digest}:revision"),
        )
        self._conn.commit()

    # ── 提交幂等(原版 analysis:active:{contentHash}:{goalDigest} SETNX)──
    def claim_active(self, media_id: str, digest: str) -> bool:
        key = f"active:{media_id}:{digest}"
        try:
            self._conn.execute(
                "INSERT INTO active_tasks(idempotency_key, media_id, goal_digest, created_at) VALUES(?,?,?,?)",
                (key, media_id, digest, datetime.now(timezone.utc).isoformat()),
            )
            self._conn.commit()
            return True
        except sqlite3.IntegrityError:
            return False

    def release_active(self, media_id: str, digest: str) -> None:
        self._conn.execute(
            "DELETE FROM active_tasks WHERE idempotency_key=?", (f"active:{media_id}:{digest}",)
        )
        self._conn.commit()

    def has_active(self, media_id: str, digest: str) -> bool:
        row = self._conn.execute(
            "SELECT 1 FROM active_tasks WHERE idempotency_key=?", (f"active:{media_id}:{digest}",)
        ).fetchone()
        return row is not None

    # ── 失败台账(原版 failed_analysis_tasks,含脱敏)────────────────
    def record_failure(
        self,
        media_id: str | None,
        digest: str | None,
        mode: str,
        stage: str,
        error_type: str,
        error_message: str,
    ) -> int:
        import re

        sanitized = re.sub(
            r"(bearer\s+\S+|api_key=\S+|sk-\S+)", "****", error_message, flags=re.IGNORECASE
        )[:1000]
        cur = self._conn.execute(
            "INSERT INTO failed_tasks(media_id, goal_digest, mode, stage, error_type, error_message, created_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (media_id, digest, mode, stage, error_type, sanitized, datetime.now(timezone.utc).isoformat()),
        )
        self._conn.commit()
        return cur.lastrowid

    def list_failures(self, limit: int = 100) -> list[dict]:
        rows = self._conn.execute(
            "SELECT id, media_id, goal_digest, mode, stage, error_type, error_message, created_at "
            "FROM failed_tasks ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
        keys = ["id", "mediaId", "goalDigest", "mode", "stage", "errorType", "errorMessage", "createdAt"]
        return [dict(zip(keys, r)) for r in rows]

    # ── Redis 热缓存(可选;写库后提交才写缓存)────────────────────
    def _cache_put(self, media_id: str, key: str, stage: str | None, payload: dict | None) -> None:
        if self._redis is None:
            return
        try:
            import json as _json

            self._redis.hset(
                f"dovideo:checkpoint:{media_id}",
                key,
                _json.dumps({"stage": stage, "payload": payload}, ensure_ascii=False),
            )
        except Exception:  # 缓存失效只影响速度
            pass

    def _cache_get(self, media_id: str, key: str) -> tuple[str | None, dict | None] | None:
        if self._redis is None:
            return None
        try:
            raw = self._redis.hget(f"dovideo:checkpoint:{media_id}", key)
            if raw is None:
                return None
            import json as _json

            data = _json.loads(raw)
            return data.get("stage"), data.get("payload")
        except Exception:
            self._cache_invalidate_prefix(media_id, key)
            return None

    def _cache_invalidate_prefix(self, media_id: str, prefix: str) -> None:
        if self._redis is None:
            return
        try:
            map_key = f"dovideo:checkpoint:{media_id}"
            for field in list(self._redis.hkeys(map_key)):
                if field.startswith(prefix):
                    self._redis.hdel(map_key, field)
        except Exception:
            pass

    def close(self) -> None:
        self._conn.close()
