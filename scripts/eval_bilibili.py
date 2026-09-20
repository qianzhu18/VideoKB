"""对 B 站《计算机专业面试总结》合集跑金标评测。

前提:scripts/ingest_bilibili.py 已把 13 期视频入过库(media:context 已有,评测不重烧 ASR/OCR)。
评测 goal 与入库 goal 不同 → 不命中终态 checkpoint,真实走 Planner→Executor→Critic。

用法(在 dovideo/ 目录下):
    .venv/bin/python scripts/eval_bilibili.py [--cases 01 03] [--out evaluation/reports/]

输出:evaluation/reports/bilibili-mianshi-golden-<时间戳>.json + 控制台汇总
"""

from __future__ import annotations

import argparse
import asyncio
import datetime
import json
import os
import pathlib
import sys

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
    parser.add_argument("--dataset", default=str(DOVIDEO_ROOT / "evaluation/golden-bilibili-mianshi.json"))
    parser.add_argument("--cases", nargs="*", default=[], help="只跑指定序号(01 02 ...),默认全部")
    parser.add_argument("--out", default=str(DOVIDEO_ROOT / "evaluation/reports"))
    args = parser.parse_args()

    load_dotenv(DOVIDEO_ROOT / ".env")

    from dovideo.config import Settings
    from dovideo.evaluation.golden import CaseReport, GoldenCase, evaluate_case
    from dovideo.retrieval.store import CompositeVectorStore, LocalVectorStore, QdrantStore

    settings = Settings.from_env()
    if not settings.llm_configured:
        print("[abort] SILICONFLOW_API_KEY 未配置")
        return 1

    from dovideo.api.app import build_default_deps
    from dovideo.checkpoints.store import CheckpointStore
    from dovideo.core.models import VideoContext

    deps = build_default_deps(settings)
    deps.broadcast = lambda *a: None
    qdrant = None
    if settings.qdrant_url:
        qdrant = QdrantStore(
            url=settings.qdrant_url,
            api_key=settings.qdrant_api_key,
            collection=settings.qdrant_collection,
        )
    deps.vector_store = CompositeVectorStore(qdrant=qdrant, local=LocalVectorStore())

    dataset = json.loads(pathlib.Path(args.dataset).read_text())
    raw_cases = dataset["cases"]
    if args.cases:
        wanted = {c.zfill(2) for c in args.cases}
        raw_cases = [c for c in raw_cases if c["mediaId"].rsplit("-", 1)[-1] in wanted]
    if not raw_cases:
        print("[eval] 没有匹配的评测用例")
        return 1

    # 金标评测的证据核验要直接读 context.segments → 从 checkpoint 显式加载
    # (同时 run_analysis_job 也会复用它,不重烧 ASR/OCR)
    db_path = pathlib.Path(settings.db_path)
    if not db_path.is_absolute():
        db_path = DOVIDEO_ROOT / db_path
    store = CheckpointStore(db_path)
    cases: list[GoldenCase] = []
    for c in raw_cases:
        _, ctx_payload = store.load(c["mediaId"], "media:context")
        if not ctx_payload:
            print(f"[eval] {c['mediaId']} 未入库,跳过")
            continue
        cases.append(
            GoldenCase(
                case_id=c["mediaId"],
                goal=c["goal"],
                expected_keywords=c["expectedKeywords"],
                context=VideoContext.model_validate(ctx_payload),
            )
        )

    print(f"[eval] 用例 {len(cases)} 个,开始评测…", flush=True)
    # 逐条评测 + 单条容错:API 抖动/超时的用例记 FAILED,不拖垮整场
    reports: list[CaseReport] = []
    for case in cases:
        print(f"[eval] {case.case_id} → {case.goal[:36]}…", flush=True)
        try:
            reports.append(await evaluate_case(case, deps))
        except Exception as exc:  # noqa: BLE001
            print(f"[eval] {case.case_id} 异常: {type(exc).__name__}: {str(exc)[:160]}", flush=True)
            reports.append(
                CaseReport(
                    case_id=case.case_id, structured_valid=False,
                    claim_support_rate=0.0, keyword_coverage=0.0, passed=False,
                    detail={"error": f"{type(exc).__name__}: {str(exc)[:300]}"},
                )
            )
    passed_n = sum(1 for r in reports if r.passed)
    report = {
        "total": len(reports),
        "passed": passed_n,
        "passRate": passed_n / len(reports) if reports else 0.0,
        "cases": [r.to_dict() for r in reports],
    }

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_path = out_dir / f"bilibili-mianshi-golden-{ts}.json"
    out_path.write_text(
        json.dumps({"dataset": dataset["name"], "generatedAt": ts, **report}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"\n===== 金标评测 {report['passed']}/{report['total']} 通过 ({report['passRate']:.0%}) =====")
    title_by_id = {c["mediaId"]: c["title"] for c in dataset["cases"]}
    for r in report["cases"]:
        mark = "✅" if r["passed"] else "❌"
        print(
            f"{mark} {r['caseId']} {title_by_id.get(r['caseId'], '')}\n"
            f"   结构={r['structuredValid']} 证据支持率={r['claimEvidenceSupportRate']:.2f} "
            f"关键词覆盖={r['keywordCoverage']:.2f}"
        )
    print(f"\n报告已写入 {out_path}")
    return 0 if report["passRate"] >= 0.8 else 2


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
