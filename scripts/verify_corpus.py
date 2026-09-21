"""Verify a local video directory against a versioned VideoKB corpus manifest.

Usage:
    .venv/bin/python scripts/verify_corpus.py --media-dir /path/to/videos

The command is read-only. It checks the numbered MP4 files, size, SHA-256 and
duration before an expensive ingest run starts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


DOVIDEO_ROOT = Path(__file__).resolve().parent.parent
SEQUENCE_PATTERN = re.compile(r"^(\d{2})-")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def duration_ms(path: Path) -> int:
    result = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(path),
        ],
        capture_output=True,
        check=False,
        text=True,
    )
    if result.returncode != 0:
        message = result.stderr.strip() or "ffprobe failed without an error message"
        raise RuntimeError(message)
    return round(float(result.stdout.strip()) * 1000)


def numbered_media(media_dir: Path) -> dict[str, Path]:
    matches: dict[str, Path] = {}
    for path in sorted(media_dir.glob("*.mp4")):
        matched = SEQUENCE_PATTERN.match(path.name)
        if matched is None:
            continue
        sequence = matched.group(1)
        if sequence in matches:
            raise ValueError(f"duplicate sequence {sequence}: {matches[sequence].name}, {path.name}")
        matches[sequence] = path
    return matches


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--media-dir", required=True, type=Path)
    parser.add_argument(
        "--dataset",
        type=Path,
        default=DOVIDEO_ROOT / "evaluation/technical-learning-v1.json",
    )
    parser.add_argument(
        "--duration-tolerance-ms",
        type=int,
        default=1_000,
        help="Accepted absolute duration difference per file, default 1000 ms",
    )
    args = parser.parse_args()

    if not args.media_dir.is_dir():
        print(f"[fail] media directory does not exist: {args.media_dir}", file=sys.stderr)
        return 2
    if args.duration_tolerance_ms < 0:
        print("[fail] --duration-tolerance-ms must be non-negative", file=sys.stderr)
        return 2

    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    expected = {case["sequence"]: case for case in dataset["cases"]}
    actual = numbered_media(args.media_dir)
    errors: list[str] = []

    if len(actual) != dataset["expectedMediaCount"]:
        errors.append(
            f"expected {dataset['expectedMediaCount']} numbered MP4 files, found {len(actual)}"
        )
    if set(actual) != set(expected):
        errors.append(
            f"sequence mismatch: expected {sorted(expected)}, found {sorted(actual)}"
        )

    total_bytes = 0
    total_duration_ms = 0
    for sequence, case in expected.items():
        path = actual.get(sequence)
        if path is None:
            continue
        size = path.stat().st_size
        total_bytes += size
        actual_duration_ms = duration_ms(path)
        total_duration_ms += actual_duration_ms
        actual_hash = sha256(path)
        checks = {
            "bytes": size == case["expectedBytes"],
            "sha256": actual_hash == case["sha256"],
            "duration": abs(actual_duration_ms - case["expectedDurationMs"])
            <= args.duration_tolerance_ms,
        }
        failed = [name for name, ok in checks.items() if not ok]
        if failed:
            errors.append(f"{sequence} ({case['topic']}): " + ", ".join(failed))
        else:
            print(f"[ok] {sequence} {case['topic']}")

    if total_bytes != dataset["expectedTotalBytes"]:
        errors.append(
            f"total bytes mismatch: expected {dataset['expectedTotalBytes']}, found {total_bytes}"
        )
    if abs(total_duration_ms - dataset["expectedTotalDurationMs"]) > (
        len(expected) * args.duration_tolerance_ms
    ):
        errors.append(
            "total duration mismatch: "
            f"expected {dataset['expectedTotalDurationMs']} ms, found {total_duration_ms} ms"
        )

    if errors:
        for error in errors:
            print(f"[fail] {error}", file=sys.stderr)
        return 1
    print(
        f"[pass] {dataset['name']}: {len(expected)} files, "
        f"{total_bytes} bytes, {total_duration_ms} ms"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
