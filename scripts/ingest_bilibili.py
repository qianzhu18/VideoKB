"""把 B 站合集视频批量注册进 dovideo 知识库并触发分析入库。

用法(在 dovideo/ 目录下运行,保证 dovdeo.db / data 相对路径一致):
    .venv/bin/python scripts/ingest_bilibili.py data/media/bilibili-mianshi [--only 01] [--limit N]

- 自动加载 dovideo/.env(SILICONFLOW_API_KEY / QDRANT_*)
- media_id = bili-mianshi-<序号>,稳定可重跑;已入库视频命中 media:context/media:chunks
  checkpoint,不重烧 ASR/OCR
- 每个视频:VideoContext(ASR∥OCR) → 5min 分块 → Qdrant+本地双路入库 → Agent 分析
"""

from __future__ import annotations

import argparse
import asyncio
import os
import pathlib
import sys
import time

DOVIDEO_ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(DOVIDEO_ROOT / "src"))


def load_dotenv(path: pathlib.Path) -> None:
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            os.environ.setdefault(key.strip(), value.strip())


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("media_dir", type=str)
    parser.add_argument("--goal", default="总结这个视频的核心考点,并列出高频面试问题")
    parser.add_argument("--only", default="", help="只处理文件名包含该子串的视频")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--concurrency", type=int, default=1, help="同时分析的视频数(默认 1)")
    args = parser.parse_args()

    load_dotenv(DOVIDEO_ROOT / ".env")

    from dovideo.api.app import (
        AppState,
        EventBus,
        _register_media_builder,
        build_default_deps,
    )
    from dovideo.config import Settings
    from dovideo.core.analysis import AnalysisMode
    from dovideo.jobs.worker import InlineJobRunner
    from dovideo.retrieval.store import CompositeVectorStore, LocalVectorStore, QdrantStore

    settings = Settings.from_env()
    if not settings.llm_configured:
        print("[abort] SILICONFLOW_API_KEY 未配置,拒绝用 FakeLLM 污染知识库")
        return 1

    deps = build_default_deps(settings)
    deps.broadcast = lambda *a: None

    qdrant = None
    if settings.qdrant_url:
        qdrant = QdrantStore(
            url=settings.qdrant_url,
            api_key=settings.qdrant_api_key,
            collection=settings.qdrant_collection,
        )
        print(f"[store] Qdrant {settings.qdrant_url} collection={settings.qdrant_collection}")
    else:
        print("[store] 未配置 QDRANT_URL,仅本地内存向量(进程退出即失)")
    deps.vector_store = CompositeVectorStore(qdrant=qdrant, local=LocalVectorStore())

    state = AppState(deps=deps, runner=InlineJobRunner(deps), bus=EventBus())

    media_files = sorted(pathlib.Path(args.media_dir).glob("*.mp4"))
    if args.only:
        media_files = [f for f in media_files if args.only in f.name]
    if args.limit:
        media_files = media_files[: args.limit]
    if not media_files:
        print(f"[ingest] {args.media_dir} 下没有 mp4")
        return 1

    print(f"[ingest] 共 {len(media_files)} 个视频待入库 goal={args.goal!r} 并发={args.concurrency}")

    # 预算熔断的终态不释放幂等锁;批量重跑前按死信重放语义清掉残留锁
    import sqlite3 as _sq

    db_path = pathlib.Path(settings.db_path)
    if not db_path.is_absolute():
        db_path = DOVIDEO_ROOT / db_path
    db = _sq.connect(db_path)
    stale = db.execute(
        "DELETE FROM active_tasks WHERE media_id LIKE 'bili-mianshi-%'"
    ).rowcount
    db.commit()
    db.close()
    if stale:
        print(f"[ingest] 清理残留幂等锁 {stale} 条")

    async def ingest_one(f: pathlib.Path, sem: asyncio.Semaphore) -> tuple[str, str, float, str]:
        seq = f.name.split("-")[0]
        media_id = f"bili-mianshi-{seq}"
        _register_media_builder(state, media_id, str(f.resolve()))
        t0 = time.monotonic()
        async with sem:
            print(f"\n=== {f.name} → {media_id} 开始 ===", flush=True)
            try:
                outcome = await state.runner.submit(media_id, args.goal, AnalysisMode.GENERAL)
                elapsed = time.monotonic() - t0
                _, chunks_payload = deps.store.load(media_id, "media:chunks")
                n_chunks = len(chunks_payload) if chunks_payload else 0
                status = "PASSED" if outcome.passed else f"UNPASSED(rounds={outcome.rounds})"
                print(f"[done] {media_id} {status} chunks={n_chunks} 耗时={elapsed:.0f}s", flush=True)
                return (f.name, status, elapsed, media_id)
            except Exception as exc:  # noqa: BLE001 — 批量任务单条失败不阻断后续
                elapsed = time.monotonic() - t0
                print(f"[FAIL] {media_id} 耗时={elapsed:.0f}s {type(exc).__name__}: {exc}", flush=True)
                return (f.name, "FAILED", elapsed, media_id)

    sem = asyncio.Semaphore(args.concurrency)
    results = list(
        await asyncio.gather(*(ingest_one(f, sem) for f in media_files))
    )

    print("\n===== 汇总 =====")
    for name, status, elapsed, _mid in results:
        print(f"{status:>20}  {elapsed:>6.0f}s  {name}")
    failed = sum(1 for _, s, _e, _m in results if s == "FAILED")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
