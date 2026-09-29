#!/usr/bin/env python3
"""One-shot corpus bootstrap for the Java knowledge base.

Wires the zero-reburn migration path end to end against the running backend:
login -> ensure space/collection -> local-directory ingest scan (analyze=false,
files hosted in MinIO but ASR not dispatched) -> import archived transcripts for
every video except the ASR pilot -> dispatch a real ASR run for the pilot.

Usage:
    python3 scripts/import_corpus.py --seed evaluation/seeds/bilibili-mianshi-v1.json \
        [--asr-seq 1] [--skip-asr] [--dry-run]

Credentials come from .env (DOVIDEO_API_USERNAME / DOVIDEO_API_PASSWORD).
"""

import argparse
import json
import os
import re
import sys
import time
import functools
import urllib.request

print = functools.partial(print, flush=True)

BASE_URL = os.environ.get("DOVIDEO_API_BASE", "http://127.0.0.1:9090")
SPACE_NAME = "技术面试知识库"
COLLECTION_NAME = "B站面试八股合集"
INGEST_GOAL = "总结视频中的核心概念、关键流程与容易混淆的点。"
ASR_GOAL = "完整解析这个视频的内容，提取带时间戳的要点"


def read_env(path=".env"):
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


class Api:
    def __init__(self, base_url, token=None):
        self.base_url = base_url
        self.token = token

    def call(self, method, path, body=None, params=None):
        url = self.base_url + path
        if params:
            url += "?" + "&".join(f"{k}={urllib.request.quote(str(v))}" for k, v in params.items())
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        with urllib.request.urlopen(request, timeout=300) as response:
            payload = json.loads(response.read().decode())
        if payload.get("code") != 0:
            raise RuntimeError(f"{method} {path} failed: {payload.get('code')} {payload.get('message')}")
        return payload.get("data")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", default="evaluation/seeds/bilibili-mianshi-v1.json")
    parser.add_argument("--root", help="Media directory; defaults to the seed archive layout")
    parser.add_argument("--asr-seq", type=int, default=1, help="Sequence number that runs REAL ASR")
    parser.add_argument("--skip-asr", action="store_true")
    parser.add_argument("--dry-run", action="store_true", help="Plan the scan only, import nothing")
    parser.add_argument("--skip-import", action="store_true", help="Scan only, no transcript import")
    args = parser.parse_args()

    env = read_env()
    username, password = env.get("DOVIDEO_API_USERNAME"), env.get("DOVIDEO_API_PASSWORD")
    if not username or not password:
        sys.exit("DOVIDEO_API_USERNAME/PASSWORD missing from .env")

    seed = json.load(open(args.seed, encoding="utf-8"))
    videos = seed["videos"]
    root = args.root
    if not root:
        sys.exit("--root is required (the directory the 13 videos live in)")

    api = Api(BASE_URL)
    auth = api.call("POST", "/user/login", {"username": username, "password": password})
    api.token = auth["token"]
    print(f"[1] logged in as {username} (userId={auth['userInfo']['id']})")

    spaces = api.call("GET", "/knowledge/spaces")
    space = next((s for s in spaces if s["name"] == SPACE_NAME), None)
    if space is None:
        space = api.call("POST", "/knowledge/spaces",
                         {"name": SPACE_NAME, "description": "13 期 B站面试八股视频的跨视频知识库"})
        print(f"[2] created space '{SPACE_NAME}' id={space['id']}")
    else:
        print(f"[2] reuse space '{SPACE_NAME}' id={space['id']}")

    collections = api.call("GET", f"/knowledge/spaces/{space['id']}/collections")
    collection = next((c for c in collections if c["name"] == COLLECTION_NAME), None)
    if collection is None:
        collection = api.call("POST", f"/knowledge/spaces/{space['id']}/collections",
                              {"name": COLLECTION_NAME, "sortOrder": 1})
        print(f"[3] created collection '{COLLECTION_NAME}' id={collection['id']}")
    else:
        print(f"[3] reuse collection '{COLLECTION_NAME}' id={collection['id']}")

    dry = args.dry_run
    scan = api.call("POST", "/knowledge/ingest/scan", {
        "rootPath": root, "spaceId": space["id"],
        "collectionId": collection["id"], "dryRun": dry, "analyze": False})
    print(f"[4] ingest scan {'(dry run)' if dry else 'applied'}: "
          f"created={scan['createdCount']} unchanged={scan['unchangedCount']} "
          f"changed={scan['changedCount']} moved={scan['movedCount']} "
          f"deleted={scan['deletedCount']} error={scan['errorCount']}")
    if dry:
        for action in json.loads(scan["plan"])["actions"]:
            print(f"    {action['action']:9s} {action['path'].split('/')[-1]}")
        return

    sources = api.call("GET", "/knowledge/sources",
                       params={"spaceId": space["id"], "collectionId": collection["id"]})
    by_seq = {}
    for source in sources:
        match = re.match(r"^(\d+)-", source["title"] or "")
        if match:
            by_seq[int(match.group(1))] = source
    missing = [v["sequence"] for v in videos if v["sequence"] not in by_seq]
    if missing:
        sys.exit(f"no registered source for sequences {missing}; scan output above")

    if args.skip_import:
        print("[5] transcript import skipped (--skip-import)")
    else:
        imported = 0
        for video in videos:
            if video["sequence"] == args.asr_seq and not args.skip_asr:
                continue
            source = by_seq[video["sequence"]]
            result = api.call("POST", "/knowledge/ingest/import-transcript", {
                "mediaId": source["mediaId"],
                "goal": INGEST_GOAL,
                "segments": video["segments"],
            })
            imported += 1
            print(f"[5] imported seq={video['sequence']:02d} {video['topic']} "
                  f"mediaId={source['mediaId']} -> {result['importedSegments']} segments")
        print(f"[5] transcript import done: {imported} video(s)")

    if args.skip_asr:
        print("[6] real-ASR dispatch skipped (--skip-asr)")
    else:
        pilot = by_seq[args.asr_seq]
        api.call("POST", f"/analysis/ai", params={
            "id": pilot["mediaId"], "goal": ASR_GOAL, "mode": "GENERAL"})
        print(f"[6] REAL ASR dispatched for seq={args.asr_seq:02d} "
              f"mediaId={pilot['mediaId']} (goal='{ASR_GOAL}') — poll /analysis/tasks")

    print("\nbootstrap complete")


if __name__ == "__main__":
    main()
