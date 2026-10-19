#!/usr/bin/env python3
"""Read-only exporter: archived Python-prototype checkpoints -> seed JSON for the Java KB.

Extracts per-video timestamped transcripts (60s windows) and agent summaries from the
retired prototype's dovideo.db so the Java knowledge base can import them without
re-burning ASR cost. This is an evaluation/data asset per AGENTS.md — not product code.

Usage:
    python3 scripts/export_archive_checkpoints.py \
        --db "../.archive/python-videoagent-retired-2026-09/dovideo/dovideo.db" \
        --manifest "../.archive/python-videoagent-retired-2026-09/dovideo/evaluation/technical-learning-v1.json" \
        --out evaluation/seeds/bilibili-mianshi-v1.json
"""

import argparse
import datetime as dt
import json
import sqlite3
import sys
from pathlib import Path

# The prototype re-ran three videos under technical-learning-* ids; the complete,
# uniform-goal corpus lives under bili-mianshi-* ids (same underlying files).
GOAL_DIGEST = "bd597710fc23c207b5828dc123dc9a95c683398d520f59f24277f7f3bac51031"


def load_media_row(conn, media_id, key):
    row = conn.execute(
        "SELECT payload FROM checkpoints WHERE media_id=? AND checkpoint_key=?",
        (media_id, key)).fetchone()
    return json.loads(row[0]) if row else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)

    videos, problems = [], []
    for case in manifest["cases"]:
        seq = str(case["sequence"]).zfill(2)
        media_id = f"bili-mianshi-{seq}"
        context = load_media_row(conn, media_id, "media:context")
        summary = load_media_row(conn, media_id, f"goal:{GOAL_DIGEST}:result")
        if not context or not summary:
            problems.append(f"{media_id}: missing {'context' if not context else 'summary'}")
            continue

        segments = [{
            "startMs": seg["start_ms"],
            "endMs": seg["end_ms"],
            "text": seg.get("transcript") or "",
            "ocr": [t for t in (seg.get("ocr_texts") or []) if t and t.strip()],
        } for seg in context["segments"]]
        empty = [s for s in segments if not s["text"].strip()]
        if empty:
            problems.append(f"{media_id}: {len(empty)} empty transcript segment(s)")

        videos.append({
            "mediaKey": media_id,
            "sequence": int(seq),
            "topic": case["topic"],
            "durationMs": segments[-1]["endMs"] if segments else 0,
            "segments": segments,
            "summary": {
                "title": summary.get("title") or "",
                "conclusions": summary.get("conclusions") or [],
                "suggestions": summary.get("suggestions") or [],
            },
        })

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps({
        "version": "bilibili-mianshi-v1",
        "exportedAt": dt.datetime.now().isoformat(timespec="seconds"),
        "source": "archived dovideo.db checkpoints (read-only)",
        "segmentWindowMs": 60000,
        "videos": videos,
    }, ensure_ascii=False, indent=1), encoding="utf-8")

    total_segments = sum(len(v["segments"]) for v in videos)
    total_chars = sum(len(s["text"]) for v in videos for s in v["segments"])
    print(f"exported {len(videos)} videos / {total_segments} segments / "
          f"{total_chars} transcript chars -> {out_path}")
    if problems:
        print("WARNINGS:", file=sys.stderr)
        for problem in problems:
            print("  -", problem, file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
