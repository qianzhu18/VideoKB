"""知识库入库质量验证：跨视频语义检索和单视频证据检索。

用法(在 dovideo/ 目录下):
    .venv/bin/python scripts/verify_retrieval.py --id-prefix technical-learning

- 跨视频:embedding 直接查 Qdrant 全库,看一个问题的证据落在哪几期视频
- 单视频:走项目真实 evidence_search(意图规划 → 混合检索 → 时间戳锚点)
"""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import sqlite3
import sys

DOVIDEO_ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(DOVIDEO_ROOT / "src"))


def load_dotenv(path: pathlib.Path) -> None:
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            os.environ.setdefault(key.strip(), value.strip())


CROSS_VIDEO_QUERIES = [
    "TCP 三次握手为什么不能改成两次",
    "Redis 缓存穿透和缓存雪崩分别怎么解决",
    "线程池的核心参数和拒绝策略",
    "HashMap 在并发下为什么线程不安全",
    "Kafka 怎么保证消息不丢失",
]

SINGLE_VIDEO_QUERIES = [
    ("01", "三次握手的过程和每一步的作用"),
    ("03", "缓存穿透、击穿、雪崩的区别和解决方案"),
]


def load_summary_index(db_path: pathlib.Path) -> dict[str, str]:
    """(mediaId, startMs) → 块摘要,用于把 Qdrant 命中还原成可读文本。"""
    index: dict[str, str] = {}
    if not db_path.exists():
        return index
    db = sqlite3.connect(db_path)
    for media_id, payload in db.execute(
        "SELECT media_id, payload FROM checkpoints WHERE checkpoint_key='media:chunks'"
    ):
        for chunk in json.loads(payload):
            key = f"{media_id}:{chunk.get('startMs')}"
            index[key] = (chunk.get("segment_summary") or "")[:80]
    db.close()
    return index


async def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id-prefix", default="technical-learning")
    args = parser.parse_args()
    load_dotenv(DOVIDEO_ROOT / ".env")

    from dovideo.api.app import build_default_deps
    from dovideo.config import Settings
    from dovideo.core.budget import AgentExecutionBudget
    from dovideo.core.models import VideoChunk, VideoContext
    from dovideo.core.telemetry import Telemetry
    from dovideo.checkpoints.store import CheckpointStore
    from dovideo.retrieval.search import evidence_search
    from dovideo.retrieval.store import CompositeVectorStore, LocalVectorStore, QdrantStore

    settings = Settings.from_env()
    deps = build_default_deps(settings)
    qdrant = QdrantStore(
        url=settings.qdrant_url, api_key=settings.qdrant_api_key,
        collection=settings.qdrant_collection,
    )
    await qdrant.ensure_collection()
    vector_store = CompositeVectorStore(qdrant=qdrant, local=LocalVectorStore())

    db_path = pathlib.Path(settings.db_path)
    if not db_path.is_absolute():
        db_path = DOVIDEO_ROOT / db_path
    summaries = load_summary_index(db_path)

    print("===== 跨视频语义检索(Qdrant 全库)=====")
    for q in CROSS_VIDEO_QUERIES:
        vectors = await deps.llm.embed([q])
        emb = vectors[0] if vectors else []
        if not emb:
            print(f"[{q}] embedding 不可用,跳过")
            continue
        resp = await qdrant._client.post(  # noqa: SLF001 — 验证脚本复用其 httpx 客户端
            f"/collections/{qdrant.collection}/points/query",
            json={"query": emb, "limit": 8, "with_payload": True},
        )
        hits = resp.json().get("result", {}).get("points", [])
        print(f"\nQ: {q}")
        for p in hits:
            payload = p.get("payload", {})
            mid = str(payload.get("mediaId", "?"))
            start = int(payload.get("startMs", 0)) // 1000
            key = f"{mid}:{payload.get('startMs')}"
            text = summaries.get(key, "")
            print(f"   {mid.split('-')[-1]}期 [{start//60:02d}:{start%60:02d}] "
                  f"score={p.get('score', 0):.3f}  {text}")

    print("\n===== 单视频证据检索(项目 evidence_search 全链路)=====")
    store = CheckpointStore(db_path)
    for sequence, q in SINGLE_VIDEO_QUERIES:
        media_id = f"{args.id_prefix}-{sequence}"
        _, ctx_payload = store.load(media_id, "media:context")
        _, chunk_payload = store.load(media_id, "media:chunks")
        if not ctx_payload or not chunk_payload:
            print(f"[{media_id}] 尚未入库,跳过")
            continue
        context = VideoContext.model_validate(ctx_payload)
        chunks = [VideoChunk.model_validate(c) for c in chunk_payload]
        budget = AgentExecutionBudget(
            max_duration_ms=300_000, max_tokens=100_000
        )
        hits = await evidence_search(
            context, chunks, q, llm=deps.llm, settings=settings,
            telemetry=Telemetry(), budget=budget,
            store=vector_store, media_id=media_id,
        )
        print(f"\nQ({media_id}): {q} → {len(hits)} 条证据")
        for h in hits[:3]:
            ts = h.get("startMs", 0) // 1000
            print(f"   [{ts//60:02d}:{ts%60:02d}] {h.get('source')}: {str(h.get('snippet', ''))[:70]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
